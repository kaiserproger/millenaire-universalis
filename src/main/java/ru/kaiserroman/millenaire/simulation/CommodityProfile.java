package ru.kaiserroman.millenaire.simulation;

/** Fixed-point commodity parameters; prices use 1000 as the base-price index. */
public record CommodityProfile(
        int basePrice,
        int targetStockPerPersonMilli,
        int productionPerWorkerMilli,
        int consumptionPerPersonMilli,
        int scarcityElasticity,
        int priceSmoothing) {

    public CommodityProfile {
        if (basePrice <= 0 || targetStockPerPersonMilli < 0
                || productionPerWorkerMilli < 0 || consumptionPerPersonMilli < 0
                || scarcityElasticity < 0 || scarcityElasticity > 4_000
                || priceSmoothing <= 0 || priceSmoothing > 1_000) {
            throw new IllegalArgumentException("Invalid commodity profile");
        }
    }
}
