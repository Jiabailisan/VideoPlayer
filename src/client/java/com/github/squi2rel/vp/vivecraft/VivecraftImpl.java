package com.github.squi2rel.vp.vivecraft;

import org.joml.Matrix4f;

class VivecraftImpl {
    static boolean isRightEye() {
        return false;
    }

    static boolean isVRActive() {
        return false;
    }

    static Matrix4f getRotation() {
        return new Matrix4f();
    }
}
