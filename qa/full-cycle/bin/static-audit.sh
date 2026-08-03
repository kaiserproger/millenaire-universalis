#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
qa_root="$(cd -- "$script_dir/.." && pwd -P)"
repo_root="$(cd -- "$qa_root/../.." && pwd -P)"
armies="$repo_root"
server_repo="${BANNEROK_SERVER_REPO:-$repo_root/../bannerok-server}"
output="${1:-$qa_root/results/static.json}"
mkdir -p -- "$(dirname -- "$output")"

json_valid=true
while IFS= read -r -d '' json; do
    jq -e . "$json" >/dev/null || json_valid=false
done < <(find "$armies/src/main/resources" -type f -name '*.json' -print0)

placeholder_free=true
if rg -n -i --glob '!**/*SelfTest.java' 'TODO|FIXME|not implemented|UnsupportedOperationException' \
        "$armies/src/main/java" "$armies/src/main/resources" >/dev/null; then
    placeholder_free=false
fi

resources_complete=true
for required in \
    assets/millenaire_armies/lang/en_us.json \
    assets/millenaire_armies/lang/ru_ru.json \
    assets/millenaire_armies/army_presentations/order_statuses/holding.json \
    assets/millenaire_armies/army_presentations/order_statuses/moving.json \
    assets/millenaire_armies/army_presentations/order_statuses/rallying.json \
    assets/millenaire_armies/army_presentations/order_statuses/supplying.json \
    data/millenaire_armies/army_unit_descriptors/roles/levy.json \
    data/millenaire_armies/army_unit_descriptors/loadouts/levy.json; do
    [[ -s "$armies/src/main/resources/$required" ]] || resources_complete=false
done

translations_complete=true
translation_keys="$(dirname -- "$output")/.translation-keys.$$"
trap 'rm -f -- "$translation_keys"' EXIT
rg -o 'Component[.]translatable\("[^"]+"' "$armies/src/main/java" \
    | sed 's/^.*Component[.]translatable("//; s/"$//' \
    | rg '(^|[.])millenaire_armies([.]|$)' \
    | LC_ALL=C sort -u > "$translation_keys" || true
while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    jq -e --arg key "$key" 'has($key)' \
        "$armies/src/main/resources/assets/millenaire_armies/lang/en_us.json" >/dev/null \
        || translations_complete=false
    jq -e --arg key "$key" 'has($key)' \
        "$armies/src/main/resources/assets/millenaire_armies/lang/ru_ru.json" >/dev/null \
        || translations_complete=false
done < "$translation_keys"

harness_checked=false
qa_fail_closed=true
release_gameplay_flow=true
controller="$server_repo/stress-harness/mod/src/main/java/ru/kaiserroman/bannerokstress/ArmyFullCycleController.java"
if [[ -f "$controller" ]]; then
    harness_checked=true
    rg -q 'millenairearmies[.]qa[.]enabled' "$controller" || qa_fail_closed=false
    rg -q 'StressController[.]armed' "$controller" || qa_fail_closed=false
    rg -q 'millarmies raise ' "$controller" || release_gameplay_flow=false
    if rg -q 'millarmies (create|recruit) ' "$controller"; then
        release_gameplay_flow=false
    fi
    rg -q 'setOwner\(' "$controller" || release_gameplay_flow=false
    rg -q 'armyTargetDimension' "$controller" || release_gameplay_flow=false
fi

status=pass
failure=null
if ! $json_valid || ! $placeholder_free || ! $resources_complete || ! $translations_complete \
        || ! $qa_fail_closed || ! $release_gameplay_flow; then
    status=fail
    failure='"static army QA invariant failed"'
fi

jq -nS \
    --arg status "$status" \
    --argjson failure "$failure" \
    --argjson jsonValid "$json_valid" \
    --argjson placeholderFree "$placeholder_free" \
    --argjson resourcesComplete "$resources_complete" \
    --argjson translationsComplete "$translations_complete" \
    --argjson harnessChecked "$harness_checked" \
    --argjson qaFailClosed "$qa_fail_closed" \
    --argjson releaseGameplayFlow "$release_gameplay_flow" \
    '{schema:"bannerok.army-full-cycle-static.v1",status:$status,failure:$failure,checks:{jsonValid:$jsonValid,placeholderFree:$placeholderFree,resourcesComplete:$resourcesComplete,translationsComplete:$translationsComplete,externalHarnessChecked:$harnessChecked,qaFailClosed:$qaFailClosed,releaseGameplayFlow:$releaseGameplayFlow}}' \
    > "$output"
jq -e '.status == "pass"' "$output" >/dev/null
