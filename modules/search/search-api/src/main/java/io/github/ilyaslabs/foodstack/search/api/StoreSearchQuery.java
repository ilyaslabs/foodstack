package io.github.ilyaslabs.foodstack.search.api;

/**
 * A customer's store search.
 *
 * @param lat customer latitude
 * @param lng customer longitude
 * @param text free text matched against brand, branch, cuisines and dishes in every language; may be {@code null}
 * @param cuisine a cuisine tag to filter on; may be {@code null}
 * @param fulfilment delivery (the branch delivers to the point) or pickup (the branch is close and offers pickup)
 * @param page zero-based page
 * @param size page size, 1 to 50
 */
public record StoreSearchQuery(double lat, double lng, String text, String cuisine, Fulfilment fulfilment, int page,
        int size) {
}
