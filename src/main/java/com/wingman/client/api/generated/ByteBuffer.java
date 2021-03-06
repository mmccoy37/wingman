package com.wingman.client.api.generated;

import java.lang.SuppressWarnings;

@SuppressWarnings("all")
public interface ByteBuffer extends Node {
    byte[] getPayload();

    int getPosition();

    @SuppressWarnings("all")
    interface Unsafe extends Node {
        void setPayload(byte[] value);

        void setPosition(int value);
    }
}
