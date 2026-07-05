package com.github.squi2rel.vp.folia.provider;

public record NamedProviderSource(String name) implements IProviderSource {
    @Override
    public void reply(String text) {
    }
}
