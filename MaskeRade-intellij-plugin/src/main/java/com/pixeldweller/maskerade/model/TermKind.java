package com.pixeldweller.maskerade.model;

/** What a term stands for — decides the placeholder word list and whether spellings are derived. */
public enum TermKind {
    PERSON("Person", false),
    COMPANY("Company", false),
    PROJECT("Project / codename", false),
    HOST("Domain / host", true),
    MAIL("Mail address", true),
    CUSTOMER("Customer", false),
    PRODUCT("Product", false),
    USERNAME("User name", false),
    SECRET("Secret", true),
    OTHER("Other", false);

    private final String label;
    private final boolean literal;

    TermKind(String label, boolean literal) {
        this.label = label;
        this.literal = literal;
    }

    public String label() {
        return label;
    }

    /** Literal kinds are replaced as they are (plus lower/upper case), without deriving camel/snake/kebab forms. */
    public boolean literal() {
        return literal;
    }

    @Override
    public String toString() {
        return label;
    }
}
