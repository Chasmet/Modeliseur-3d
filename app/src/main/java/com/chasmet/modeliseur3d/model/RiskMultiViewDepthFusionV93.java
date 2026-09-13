package com.chasmet.modeliseur3d.model;

/** V9.3: auto-detects kart+driver before fusion, then applies dedicated topology. */
public final class RiskMultiViewDepthFusionV93 {
    private RiskMultiViewDepthFusionV93() {}

    public static MultiViewDepthFusion.Result refine(
            float[] base, boolean[][] masks, float[][] depth, float[][] confidence,
            int width, int height, int depthSize, SubjectCategory category) {

        SubjectCategory effective = category == null ? SubjectCategory.AUTO : category;
        if ((effective == SubjectCategory.AUTO || effective == SubjectCategory.CHARACTER)
                && looksComposite(masks, width, height, depthSize)) {
            effective = SubjectCategory.COMPOSITE_VEHICLE;
        }

        MultiViewDepthFusion.Result first = MultiViewDepthFusion.refine(
                base, masks, depth, confidence, width, height, depthSize, effective);
        if (!first.isApplied()) return first;

        if (effective == SubjectCategory.ANIMAL) {
            AnimalLegTopologyRefiner.Result legs = AnimalLegTopologyRefiner.refine(
                    first.getDensity(), width, height, depthSize);
            if (!legs.applied) return append(first, first.getDensity(), 0,
                    first.getOccupiedVoxels(), legs.summary);
            return append(first, legs.density, legs.changed, legs.occupied, legs.summary);
        }

        if (effective == SubjectCategory.COMPOSITE_VEHICLE) {
            CompositeVehicleTopologyRefiner.Result vehicle = CompositeVehicleTopologyRefiner.refine(
                    first.getDensity(), width, height, depthSize);
            String prefix = effective != category ? "V9.3 auto kart+pilote • " : "";
            if (!vehicle.applied) return append(first, first.getDensity(), 0,
                    first.getOccupiedVoxels(), prefix + vehicle.summary);
            return append(first, vehicle.density, vehicle.changed, vehicle.occupied,
                    prefix + vehicle.summary);
        }
        return first;
    }

    private static boolean looksComposite(boolean[][] masks, int width, int height, int depth) {
        if (masks == null || masks.length != 4) return false;
        Profile front = measure(masks[0], masks[2], width, height);
        Profile side = measure(masks[1], masks[3], depth, height);
        if (!front.valid || !side.valid) return false;
        boolean frontBase = front.lowerWidth >= front.upperWidth * 1.20f
                && front.lowerFill >= front.middleFill * 0.80f
                && front.aspect >= 0.38f;
        boolean sideBase = side.lowerWidth >= side.upperWidth * 1.08f
                && side.lowerFill >= side.middleFill * 0.76f
                && side.aspect >= 0.44f;
        return (frontBase && side.lowerWidth >= side.upperWidth * 0.94f)
                || (sideBase && front.lowerWidth >= front.upperWidth * 1.05f);
    }

    private static Profile measure(boolean[] a, boolean[] b, int width, int height) {
        if (a == null || b == null || a.length != width * height || b.length != width * height)
            return Profile.invalid();
        int top = height, bottom = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (a[row + x] || b[row + (width - 1 - x)]) {
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
        }
        if (bottom <= top) return Profile.invalid();
        int span = Math.max(1, bottom - top);
        float upper = averageWidth(a,b,width,top,bottom,0.10f,0.38f);
        float middle = averageWidth(a,b,width,top,bottom,0.38f,0.62f);
        float lower = averageWidth(a,b,width,top,bottom,0.62f,0.96f);
        float middleFill = fill(a,b,width,top,bottom,0.34f,0.60f);
        float lowerFill = fill(a,b,width,top,bottom,0.60f,0.96f);
        float aspect = Math.max(upper, Math.max(middle, lower)) / Math.max(1.0f, span);
        return new Profile(true, upper, lower, middleFill, lowerFill, aspect);
    }

    private static float averageWidth(boolean[] a, boolean[] b, int width,
            int top, int bottom, float from, float to) {
        int span = Math.max(1, bottom-top);
        int y0 = top + Math.round(span*from), y1 = top + Math.round(span*to);
        float total = 0; int rows = 0;
        for (int y=y0; y<=y1 && y<=bottom; y++) {
            int left=width,right=-1,row=y*width;
            for (int x=0;x<width;x++) if (a[row+x] || b[row+(width-1-x)]) {
                left=Math.min(left,x); right=Math.max(right,x);
            }
            total += right>=left ? right-left+1 : 0; rows++;
        }
        return total/Math.max(1,rows);
    }

    private static float fill(boolean[] a, boolean[] b, int width,
            int top, int bottom, float from, float to) {
        int span=Math.max(1,bottom-top);
        int y0=top+Math.round(span*from), y1=top+Math.round(span*to);
        long occ=0,total=0;
        for (int y=y0;y<=y1 && y<=bottom;y++) {
            int row=y*width;
            for(int x=0;x<width;x++){ if(a[row+x]||b[row+(width-1-x)]) occ++; total++; }
        }
        return occ/Math.max(1.0f,(float)total);
    }

    private static MultiViewDepthFusion.Result append(MultiViewDepthFusion.Result first,
            float[] density, int changed, int occupied, String summary) {
        return new MultiViewDepthFusion.Result(density, true, first.getValidViews(),
                first.getChangedVoxels()+changed, occupied, first.getMeanSurfaceInset(),
                first.isCollapseGuardUsed(), first.getCorrespondencePrunedVoxels(),
                first.getReason()+" • "+summary);
    }

    private static final class Profile {
        final boolean valid; final float upperWidth, lowerWidth, middleFill, lowerFill, aspect;
        Profile(boolean v,float u,float l,float mf,float lf,float a){valid=v;upperWidth=u;lowerWidth=l;middleFill=mf;lowerFill=lf;aspect=a;}
        static Profile invalid(){return new Profile(false,0,0,0,0,0);}
    }
}
