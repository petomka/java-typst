package io.github.petomka.javatypst;

public final class TypstPackageNotFoundException extends Exception {
    public TypstPackageNotFoundException(String namespace, String name, String version) {
        super("Package not found: @" + namespace + "/" + name + ":" + version);
    }
}
