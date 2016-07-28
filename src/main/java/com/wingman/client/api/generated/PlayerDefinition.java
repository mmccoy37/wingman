package com.wingman.client.api.generated;

import java.lang.SuppressWarnings;

@SuppressWarnings("all")
public interface PlayerDefinition {
    void initialize(int[] arg0, int[] arg1, boolean arg2, int arg3);

    int[] getAppearanceColors();

    int[] getAppearance();

    boolean getIsFemale();

    @SuppressWarnings("all")
    interface Unsafe {
        void setAppearanceColors(int[] value);

        void setAppearance(int[] value);

        void setIsFemale(boolean value);
    }
}
