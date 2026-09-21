package net.exoego.uika.cli;

enum RefKind {
    METHOD("method"),
    INTERFACE_METHOD("interface_method"),
    FIELD("field"),
    CLASS("class");

    static final RefKind[] VALUES = values();

    final String json;

    RefKind(String json) {
        this.json = json;
    }
}
