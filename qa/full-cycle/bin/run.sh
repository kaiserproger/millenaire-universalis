#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
qa_root="$(cd -- "$script_dir/.." && pwd -P)"
repo_root="$(cd -- "$qa_root/../.." && pwd -P)"
server_repo="${BANNEROK_SERVER_REPO:-$repo_root/../bannerok-server}"
run_id="${1:-army-$(date -u +%Y%m%dT%H%M%SZ)}"
[[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || { printf 'unsafe run id\n' >&2; exit 64; }
run_dir="$qa_root/runs/$run_id"
[[ ! -e "$run_dir" ]] || { printf 'run already exists: %s\n' "$run_dir" >&2; exit 73; }

runtime_root="${BANNEROK_QA_RUNTIME_ROOT:-}"
if [[ -z "$runtime_root" ]]; then
    while IFS= read -r candidate; do
        if [[ -d "$candidate/libraries" && -d "$candidate/mods" ]]; then
            runtime_root="$candidate"
            break
        fi
    done < <({
        git -C "$server_repo" worktree list --porcelain 2>/dev/null || true
        git -C "$repo_root" worktree list --porcelain 2>/dev/null || true
    } | sed -n 's/^worktree //p' | LC_ALL=C sort -u)
fi
[[ -d "$runtime_root/libraries" && -d "$runtime_root/mods" ]] \
    || { printf 'set BANNEROK_QA_RUNTIME_ROOT to a complete stopped server runtime\n' >&2; exit 66; }

java_bin="${JAVA_BIN:-/home/kaiserroman/.sdkman/candidates/java/21.0.10-graal/bin/java}"
[[ -x "$java_bin" ]] || { printf 'Java 21 is unavailable: %s\n' "$java_bin" >&2; exit 69; }
java_home="$(cd -- "$(dirname -- "$java_bin")/.." && pwd -P)"

mkdir -p -- "$run_dir/server/mods" "$run_dir/artifacts" "$run_dir/control"
result="$run_dir/result.json"
log="$run_dir/artifacts/server-console.log"
pre_state="$run_dir/artifacts/pre-restart-state.json"
failure=''
server_pid=''
fifo_fd_open=false

write_result() {
    local status="$1" reason="${2:-}" log_sha=null state_sha=null
    local state_path="$run_dir/server/army-full-cycle-result.json"
    local dedicated=false settlement=false settlement_resources=false controlled=false recruitment=false
    local dimension=false move=false rally=false battle=false hold=false logistics=false reload=false persistence=false
    local foreign=false malformed=false resources=false no_fatal=false
    [[ -r "$log" ]] && log_sha="\"$(sha256sum "$log" | cut -d' ' -f1)\""
    [[ -r "$state_path" ]] && state_sha="\"$(sha256sum "$state_path" | cut -d' ' -f1)\""
    [[ -r "$log" ]] && grep -Fq 'Bannerok stress harness loaded; armed=true' "$log" && dedicated=true
    if [[ -r "$state_path" ]]; then
        state_has() { jq -e --arg name "$1" '.assertions | index($name) != null' "$state_path" >/dev/null; }
        state_has settlement_and_players && settlement=true
        state_has settlement_owner_and_resources && settlement_resources=true
        state_has controlled_army_create && controlled=true
        state_has recruit_membership && recruitment=true
        state_has army_dimension_persisted && dimension=true
        state_has move_physical_progress && move=true
        state_has rally_physical_progress && rally=true
        if state_has battle_owner_target_assignment \
                && state_has battle_enemy_target_assignment \
                && state_has battle_physical_damage; then
            battle=true
        fi
        state_has hold_state && hold=true
        state_has logistics_physical_progress && logistics=true
        state_has chunk_reload_membership_order && reload=true
        state_has restart_persistence && persistence=true
        state_has foreign_player_denied && foreign=true
        state_has malformed_payload_denied && malformed=true
    fi
    [[ -r "$run_dir/artifacts/static.json" ]] \
        && jq -e '.status == "pass"' "$run_dir/artifacts/static.json" >/dev/null \
        && resources=true
    if [[ -r "$log" ]] && ! rg -q '\[[^]]+/(ERROR|FATAL)\]|NullPointerException|Exception in server tick loop|---- Minecraft Crash Report ----' "$log"; then
        no_fatal=true
    fi
    jq -nS --arg status "$status" --arg failure "$reason" \
        --arg runId "$run_id" --argjson logSha256 "$log_sha" --argjson stateSha256 "$state_sha" \
        --argjson dedicated "$dedicated" --argjson settlement "$settlement" \
        --argjson settlementResources "$settlement_resources" --argjson controlled "$controlled" \
        --argjson recruitment "$recruitment" --argjson dimension "$dimension" --argjson move "$move" \
        --argjson rally "$rally" --argjson battle "$battle" --argjson hold "$hold" --argjson logistics "$logistics" \
        --argjson reload "$reload" --argjson persistence "$persistence" --argjson foreign "$foreign" \
        --argjson malformed "$malformed" --argjson noFatal "$no_fatal" --argjson resources "$resources" \
        '{schema:"bannerok.army-full-cycle-run.v1",status:$status,failure:(if $failure=="" then null else $failure end),checks:{dedicatedBoot:$dedicated,settlement:$settlement,settlementResources:$settlementResources,controlledArmy:$controlled,recruitment:$recruitment,dimensionPersisted:$dimension,move:$move,rally:$rally,battlePhysicalCombat:$battle,hold:$hold,logistics:$logistics,chunkUnloadReload:$reload,restartPersistence:$persistence,foreignDenied:$foreign,malformedDenied:$malformed,noFatalLog:$noFatal,resources:$resources},artifacts:{runId:$runId,serverLogSha256:$logSha256,stateSha256:$stateSha256}}' \
        > "$result"
}

send() {
    printf '%s\n' "$1" >&7
}

managed_alive() {
    [[ -n "$server_pid" && -d "/proc/$server_pid" ]]
}

stop_server() {
    if managed_alive; then
        send 'stop'
        local deadline=$((SECONDS + 180))
        while managed_alive; do
            ((SECONDS < deadline)) || return 1
            sleep 1
        done
        wait "$server_pid" || true
    fi
    server_pid=''
    if $fifo_fd_open; then
        exec 7>&-
        fifo_fd_open=false
    fi
}

cleanup() {
    local status=$?
    if managed_alive; then stop_server >/dev/null 2>&1 || true; fi
    if ((status != 0)); then
        if [[ -z "$failure" && -r "$result" ]]; then
            failure="$(jq -r '.failure // empty' "$result")"
        fi
        write_result fail "${failure:-runner exited with status $status}"
    fi
}
trap cleanup EXIT HUP INT TERM

"$script_dir/static-audit.sh" "$run_dir/artifacts/static.json"

JAVA_HOME="$java_home" "$repo_root/gradlew" -p "$repo_root" \
    --offline clean build --no-configuration-cache
[[ -x "$server_repo/server-optimizer/gradlew" && -f "$server_repo/stress-harness/mod/build.gradle" ]] \
    || { printf 'set BANNEROK_SERVER_REPO to bannerok-server with stress-harness\n' >&2; exit 66; }
JAVA_HOME="$java_home" "$server_repo/server-optimizer/gradlew" -p "$server_repo/stress-harness/mod" \
    --offline clean build --no-configuration-cache

ln -s -- "$runtime_root/libraries" "$run_dir/server/libraries"
for tree in millenaire millenaire-custom; do
    [[ -d "$runtime_root/$tree" ]] && cp -a --reflink=auto "$runtime_root/$tree" "$run_dir/server/$tree"
done
if [[ -d "$runtime_root/config" ]]; then
    cp -a --reflink=auto "$runtime_root/config/." "$run_dir/server/config/"
fi
rsync -a \
    --exclude='automodpack-*.jar' \
    --exclude='bannerok_authgate-*.jar' \
    --exclude='bannerok-authgate*.jar' \
    --exclude='millenaire_universalis-*.jar' \
    --exclude='bannerok-stress-harness-*.jar' \
    "$runtime_root/mods/" "$run_dir/server/mods/"
cp -a -- "$repo_root/build/libs/millenaire_universalis-1.1.0.jar" \
    "$run_dir/server/mods/"
cp -a -- "$server_repo/stress-harness/mod/build/libs/bannerok-stress-harness-1.0.0.jar" \
    "$run_dir/server/mods/"

cat > "$run_dir/server/eula.txt" <<'EOF'
eula=true
EOF
cat > "$run_dir/server/server.properties" <<'EOF'
allow-flight=true
difficulty=peaceful
enable-command-block=false
enable-query=false
enable-rcon=false
enforce-secure-profile=false
gamemode=creative
generate-structures=false
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false,"structure_overrides":[]}
level-name=army-qa-world
level-seed=6840227782638526189
level-type=minecraft\:flat
max-players=8
max-tick-time=60000
online-mode=false
server-ip=127.0.0.1
server-port=0
simulation-distance=6
spawn-animals=false
spawn-monsters=false
spawn-npcs=true
sync-chunk-writes=true
view-distance=6
white-list=false
EOF
cat > "$run_dir/server/user_jvm_args.txt" <<EOF
-Xms512M
-Xmx2G
-XX:+UseG1GC
-Djava.awt.headless=true
-Dbannerok.stress.enabled=true
-Dmillenairearmies.qa.enabled=true
-Dmillenairearmies.orderExecutionEnabled=true
-Dmillenairearmies.logisticsInventoryProjectionEnabled=true
-Dsarvaroptimizer.millenaire.boundedVillageActivation=true
-Dsarvaroptimizer.millenaire.villageActivationChunksPerTick=2
-Dsarvaroptimizer.millenaire.villageActivationBudgetMillis=8
EOF
printf '%s\n' BANNEROK_STRESS_SANDBOX_V1 > "$run_dir/server/.bannerok-stress-sandbox"

start_server() {
    rm -f -- "$run_dir/control/console.fifo"
    mkfifo -m 0600 -- "$run_dir/control/console.fifo"
    exec 7<> "$run_dir/control/console.fifo"
    fifo_fd_open=true
    local start_line=$(( $(wc -l < "$log") + 1 ))
    (
        cd -- "$run_dir/server"
        exec "$java_bin" @user_jvm_args.txt \
            @libraries/net/neoforged/neoforge/21.1.247/unix_args.txt nogui \
            < "$run_dir/control/console.fifo" >> "$log" 2>&1
    ) &
    server_pid=$!
    local deadline=$((SECONDS + 300))
    while managed_alive; do
        tail -n +"$start_line" "$log" | grep -Fq 'Bannerok stress harness loaded; armed=true' && return 0
        ((SECONDS < deadline)) || return 1
        sleep 2
    done
    return 1
}

wait_marker() {
    local marker="$1"
    local timeout_seconds="$2"
    local deadline=$((SECONDS + timeout_seconds))
    while ((SECONDS < deadline)); do
        grep -Fq "\"event\":\"$marker\"" "$log" && return 0
        if grep -Fq '"event":"full_cycle_fail"' "$log"; then return 1; fi
        managed_alive || return 1
        sleep 2
    done
    return 1
}

spawn_players() {
    local start_line=$(( $(wc -l < "$log") + 1 ))
    send 'player armyowner spawn at 0 100 0'
    send 'player armyforeign spawn at 2 100 0'
    local deadline=$((SECONDS + 60))
    while ((SECONDS < deadline)); do
        if tail -n +"$start_line" "$log" | grep -Fq 'armyowner joined the game' \
                && tail -n +"$start_line" "$log" | grep -Fq 'armyforeign joined the game'; then
            return 0
        fi
        managed_alive || return 1
        sleep 1
    done
    return 1
}

: > "$log"
failure='first dedicated boot failed'
start_server || { write_result fail "$failure"; exit 1; }
spawn_players || { failure='Carpet controlled players did not join'; write_result fail "$failure"; exit 1; }
send 'bannerokstress armycycle begin armyowner armyforeign'
if ! wait_marker pre_restart_pass 900; then
    failure='pre-restart army cycle failed or timed out'
    if [[ -r "$run_dir/server/army-full-cycle-result.json" ]]; then
        failure="$(jq -r '.failure // empty' "$run_dir/server/army-full-cycle-result.json")"
        [[ -n "$failure" ]] || failure='pre-restart army cycle failed without a failure code'
    fi
    write_result fail "$failure"
    exit 1
fi
cp -a -- "$run_dir/server/army-full-cycle-result.json" "$pre_state"
send 'save-all flush'
sleep 5
stop_server || { failure='first dedicated shutdown timed out'; write_result fail "$failure"; exit 1; }

failure='restart dedicated boot failed'
start_server || { write_result fail "$failure"; exit 1; }
spawn_players || { failure='controlled players did not rejoin after restart'; write_result fail "$failure"; exit 1; }
send 'bannerokstress armycycle resume armyowner armyforeign'
wait_marker full_cycle_pass 600 \
    || { failure='post-restart army cycle failed or timed out'; write_result fail "$failure"; exit 1; }
send 'save-all flush'
sleep 3
stop_server || { failure='final dedicated shutdown timed out'; write_result fail "$failure"; exit 1; }

fatal_log=false
if rg -n '\[[^]]+/(ERROR|FATAL)\]|NullPointerException|Exception in server tick loop|---- Minecraft Crash Report ----' \
        "$log" > "$run_dir/artifacts/fatal-lines.txt"; then
    fatal_log=true
fi
state="$run_dir/server/army-full-cycle-result.json"
jq -e '.status == "pass"' "$state" >/dev/null \
    || { failure='in-server machine result is not pass'; write_result fail "$failure"; exit 1; }
$fatal_log && { failure='server log contains ERROR/FATAL/NPE/crash markers'; write_result fail "$failure"; exit 1; }

assertion() {
    jq -e --arg name "$1" '.assertions | index($name) != null' "$state" >/dev/null \
        || jq -e --arg name "$1" '.assertions | index($name) != null' "$pre_state" >/dev/null
}
jq -nS \
    --arg runId "$run_id" \
    --arg logSha256 "$(sha256sum "$log" | cut -d' ' -f1)" \
    --arg stateSha256 "$(sha256sum "$state" | cut -d' ' -f1)" \
    --arg preStateSha256 "$(sha256sum "$pre_state" | cut -d' ' -f1)" \
    --arg modsSha256 "$(find "$run_dir/server/mods" -maxdepth 1 -type f -name '*.jar' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)" \
    --argjson settlement "$(assertion settlement_and_players && echo true || echo false)" \
    --argjson settlementResources "$(assertion settlement_owner_and_resources && echo true || echo false)" \
    --argjson controlled "$(assertion controlled_army_create && echo true || echo false)" \
    --argjson recruitment "$(assertion recruit_membership && echo true || echo false)" \
    --argjson dimension "$(assertion army_dimension_persisted && assertion restart_dimension_persistence && echo true || echo false)" \
    --argjson move "$(assertion move_physical_progress && echo true || echo false)" \
    --argjson rally "$(assertion rally_physical_progress && echo true || echo false)" \
    --argjson battle "$(assertion battle_owner_target_assignment \
            && assertion battle_enemy_target_assignment \
            && assertion battle_physical_damage \
            && echo true || echo false)" \
    --argjson hold "$(assertion hold_state && echo true || echo false)" \
    --argjson logistics "$(assertion logistics_physical_progress && echo true || echo false)" \
    --argjson reload "$(assertion chunk_unload && assertion chunk_reload_membership_order && echo true || echo false)" \
    --argjson persistence "$(assertion restart_persistence && assertion restart_execution_continuation && echo true || echo false)" \
    --argjson foreign "$(assertion foreign_player_denied && echo true || echo false)" \
    --argjson malformed "$(assertion malformed_payload_denied && echo true || echo false)" \
    '{schema:"bannerok.army-full-cycle-run.v1",status:"pass",failure:null,checks:{dedicatedBoot:true,settlement:$settlement,settlementResources:$settlementResources,controlledArmy:$controlled,recruitment:$recruitment,dimensionPersisted:$dimension,move:$move,rally:$rally,battlePhysicalCombat:$battle,hold:$hold,logistics:$logistics,chunkUnloadReload:$reload,restartPersistence:$persistence,foreignDenied:$foreign,malformedDenied:$malformed,noFatalLog:true,resources:true},artifacts:{runId:$runId,serverLogSha256:$logSha256,stateSha256:$stateSha256,preRestartStateSha256:$preStateSha256,modsSha256:$modsSha256}}' \
    > "$result"
jq -e '.status == "pass" and ([.checks[]] | all)' "$result" >/dev/null
cp -a -- "$result" "$qa_root/results/current.json"
printf 'Army full-cycle PASS: %s\n' "$result"
trap - EXIT HUP INT TERM
