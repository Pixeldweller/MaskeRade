package de.falkenrath.kranich.export;

/**
 * Creates exporters by class name.
 *
 * <p>MaskeRade case "reflection string": the fully qualified class name below is renamed together with the
 * package directories, so the mirror keeps working.
 */
public final class ExporterFactory {

    static final String DEFAULT_EXPORTER = "de.falkenrath.kranich.export.CsvExporter";

    private ExporterFactory() {}

    public static TourExporter createDefault() {
        return create(DEFAULT_EXPORTER);
    }

    public static TourExporter create(String className) {
        try {
            return (TourExporter) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No exporter " + className, e);
        }
    }
}
