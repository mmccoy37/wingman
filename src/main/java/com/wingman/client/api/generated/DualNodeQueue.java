package com.wingman.client.api.generated;

import java.lang.SuppressWarnings;

@SuppressWarnings("all")
public interface DualNodeQueue {
    void clear();

    void add(DualNode arg0);

    DualNode pop();

    @SuppressWarnings("all")
    interface Unsafe {
    }
}
