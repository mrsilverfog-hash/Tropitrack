package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Set;

public class ShinyBeamRenderer {

    // Couleur dorée du faisceau (R, G, B, A de 0.0 à 1.0)
    private static final float R = 1.0f;
    private static final float G = 0.85f;
    private static final float B = 0.1f;
    private static final float A = 1.0f;

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        Set<PokemonEntity> shinies = TropiTrackerClient.getActiveShinyEntities();
        if (shinies.isEmpty()) return;

        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        Vec3d playerEyes = client.player.getEyePos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();

        // --- Ligne du faisceau, du Pokémon shiny vers le joueur ---
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (PokemonEntity pe : shinies) {
            if (pe == null || pe.isRemoved()) continue;

            Vec3d entityCenter = pe.getLerpedPos(tickDelta).add(0, pe.getHeight() / 2.0, 0);
            Vec3d direction = playerEyes.subtract(entityCenter).normalize();
            double distance = Math.max(0, entityCenter.distanceTo(playerEyes) - 1.0);

            float sx = (float) (entityCenter.x - camPos.x);
            float sy = (float) (entityCenter.y - camPos.y);
            float sz = (float) (entityCenter.z - camPos.z);

            float ex = (float) (entityCenter.x - camPos.x + direction.x * distance);
            float ey = (float) (entityCenter.y - camPos.y + direction.y * distance);
            float ez = (float) (entityCenter.z - camPos.z + direction.z * distance);

            buffer.vertex(viewMatrix, sx, sy, sz).color(R, G, B, A);
            buffer.vertex(viewMatrix, ex, ey, ez).color(R, G, B, A);
        }

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        // --- Texte de distance flottant devant le joueur, visible à travers les blocs ---
        TextRenderer textRenderer = client.textRenderer;
        MatrixStack matrices = context.matrixStack();

        for (PokemonEntity pe : shinies) {
            if (pe == null || pe.isRemoved()) continue;

            Vec3d entityCenter = pe.getLerpedPos(tickDelta).add(0, pe.getHeight() / 2.0, 0);
            double dist = entityCenter.distanceTo(playerEyes);
            String label = "\u2728 " + (int) dist + "m";

            Vec3d direction2 = entityCenter.subtract(playerEyes).normalize();
            Vec3d labelPos = playerEyes.add(direction2.multiply(1.5));

            double dx = labelPos.x - camPos.x;
            double dy = labelPos.y - camPos.y;
            double dz = labelPos.z - camPos.z;

            matrices.push();
            matrices.translate(dx, dy, dz);
            matrices.multiply(camera.getRotation());
            float scale = 0.008f;
            matrices.scale(scale, -scale, scale);

            Matrix4f textMatrix = matrices.peek().getPositionMatrix();
            int textWidth = textRenderer.getWidth(label);

            // SEE_THROUGH = visible à travers les blocs, couleur dorée
            textRenderer.draw(label, -textWidth / 2f, 0, 0xFFD700, false,
                textMatrix, client.getBufferBuilders().getEntityVertexConsumers(),
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
            client.getBufferBuilders().getEntityVertexConsumers().draw();

            matrices.pop();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }
}
