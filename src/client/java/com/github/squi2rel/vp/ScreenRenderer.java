package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import org.joml.Quaternionf;

import java.util.HashMap;

import static com.github.squi2rel.vp.VideoPlayerClient.*;

@SuppressWarnings({"resource", "DataFlowIssue"})
public class ScreenRenderer {
    private static final HashMap<Identifier, RenderLayer> quadsCache = new HashMap<>();

    private static final Quaternionf rotation = new Quaternionf();
    public static float cameraX, cameraY, cameraZ;
    public static boolean skybox;

    public static void render(WorldRenderContext ctx) {
        if (CameraRenderer.rendering) return;
        skybox = false;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("render");
        MatrixStack matrices = ctx.matrices();
        matrices.push();
        Vec3d camera = ctx.worldState().cameraRenderState.pos;
        cameraX = (float) camera.x;
        cameraY = (float) camera.y;
        cameraZ = (float) camera.z;
        if (Vivecraft.loaded && Vivecraft.isVRActive()) {
            rotation.setFromNormalized(Vivecraft.getRotation()).invert();
        } else {
            rotation.set(ctx.worldState().cameraRenderState.orientation).invert();
        }
        quadsCache.clear();
        for (ClientVideoScreen screen : screens) {
            try {
                screen.draw(matrices, (VertexConsumerProvider.Immediate) ctx.consumers());
            } catch (Exception e) {
                VideoPlayerMain.LOGGER.error("Exception while rendering", e);
            }
        }
        matrices.pop();
        profiler.pop();
        profiler.pop();
    }

    public static RenderLayer getLayer(Identifier textureId) {
        return quadsCache.computeIfAbsent(textureId, RenderLayers::entityTranslucent);
    }

    public static void rotateMatrix(MatrixStack matrices) {
        matrices.multiply(rotation);
    }
}
