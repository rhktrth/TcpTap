/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

interface TrafficObserver {
    enum Direction {
        CLIENT_TO_DESTINATION("C->D"),
        DESTINATION_TO_CLIENT("D->C");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    void onData(Direction direction, byte[] data, int offset, int length);

    void onEof(Direction direction);

    void onError(Direction direction);
}

final class NoopTrafficObserver implements TrafficObserver {
    static final NoopTrafficObserver INSTANCE = new NoopTrafficObserver();

    private NoopTrafficObserver() {
    }

    @Override
    public void onData(Direction direction, byte[] data, int offset, int length) {
        // No capture is configured.
    }

    @Override
    public void onEof(Direction direction) {
        // No capture is configured.
    }

    @Override
    public void onError(Direction direction) {
        // No capture is configured.
    }
}
