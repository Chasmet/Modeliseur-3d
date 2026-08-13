package com.chasmet.modeliseur3d.model;
final class DepthFusionPolicyBase {
    static float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    static DepthFusionZone z(float a,float b,float c,float d,float e,float f,float g,float h) {
        return new DepthFusionZone(a,b,c,d,e,f,g,h);
    }
}
