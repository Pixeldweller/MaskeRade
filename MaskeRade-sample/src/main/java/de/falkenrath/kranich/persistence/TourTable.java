package de.falkenrath.kranich.persistence;

/**
 * SQL of the (not yet connected) tour table in the database
 * {@code jdbc:postgresql://db.falkenrath.lan:5432/kranich}.
 *
 * <p>MaskeRade case "kebab/snake in texts": the table name {@code kranich_touren} is replaced in the mirror and
 * reported as "external contract" — the real database still calls it {@code kranich_touren}.
 */
public final class TourTable {

    public static final String TABLE = "kranich_touren";

    public static final String SELECT_BY_ID = "SELECT falkenrath_id, customer_id, stops FROM " + TABLE
            + " WHERE falkenrath_id = ?";

    private TourTable() {}
}
