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

    // Couleur rose fluo du faisceau shiny (R, G, B de 0.0 à 1.0)
    private static final float SHINY_R = 1.0f;
    private static final float SHINY_G = 0.08f;
    private static final float SHINY_B = 0.8f;

    // Couleur jaune moutarde du faisceau des Pokémon chassés (/track et tableau de chasse)
    private static final float TRACKED_R = 0.88f;
    private static final float TRACKED_G = 0.68f;
    private static final float TRACKED_B = 0.05f;

    // Couleur rouge du faisceau baron
    private static final float BARON_R = 1.0f;
    private static final float BARON_G = 0.15f;
    private static final float BARON_B = 0.15f;

    private static final float ALPHA = 1.0f;

    private static final int SHINY_TEXT_COLOR = 0xFF14CC;
    private static final int TRACKED_TEXT_COLOR = 0xE1AD01;
    private static final int BARON_TEXT_COLOR = 0xFF3030;

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        Set<PokemonEntity> shinies = TropiTrackerClient.getActiveShinyEntities();
        Set<PokemonEntity> barons  = TropiTrackerClient.getActiveBaronEntities();
        java.util.List<Vec3d> farShinies = RadarDetector.getFarShinyPositions();
        Set<PokemonEntity> tracked = TropiTrackerClient.getActiveTrackedEntities();
        if (shinies.isEmpty() && barons.isEmpty() && farShinies.isEmpty() && tracked.isEmpty()) return;

        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        Vec3d playerEyes = client.player.getEyePos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();

        // --- Lignes des faisceaux, du Pokémon vers le joueur ---
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        appendBeams(buffer, viewMatrix, shinies, camPos, playerEyes, tickDelta, SHINY_R, SHINY_G, SHINY_B);
        appendBeams(buffer, viewMatrix, barons,  camPos, playerEyes, tickDelta, BARON_R, BARON_G, BARON_B);
        appendPositionBeams(buffer, viewMatrix, farShinies, camPos, playerEyes, SHINY_R, SHINY_G, SHINY_B);
        appendBeams(buffer, viewMatrix, tracked, camPos, playerEyes, tickDelta, TRACKED_R, TRACKED_G, TRACKED_B);

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        // --- Textes de distance flottants devant le joueur, visibles à travers les blocs ---
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, shinies, "\u2728", SHINY_TEXT_COLOR, 0);
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, barons,  "\uD83D\uDC51", BARON_TEXT_COLOR, 12);
        drawPositionLabels(context, client, camera, camPos, playerEyes, farShinies, "\u2728", SHINY_TEXT_COLOR, 24);
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, tracked, "\uD83C\uDFAF", TRACKED_TEXT_COLOR, 36);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void appendBeams(BufferBuilder buffer, Matrix4f viewMatrix, Set<PokemonEntity> entities,
                                    Vec3d camPos, Vec3d playerEyes, float tickDelta,
                                    float r, float g, float b) {
        for (PokemonEntity pe : entities) {
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

            buffer.vertex(viewMatrix, sx, sy, sz).color(r, g, b, ALPHA);
            buffer.vertex(viewMatrix, ex, ey, ez).color(r, g, b, ALPHA);
        }
    }

    /** Faisceaux vers des positions fournies par le radar (Pokémon pas encore chargé côté client). */
    private static void appendPositionBeams(BufferBuilder buffer, Matrix4f viewMatrix, java.util.List<Vec3d> positions,
                                            Vec3d camPos, Vec3d playerEyes, float r, float g, float b) {
        for (Vec3d target : positions) {
            Vec3d direction = playerEyes.subtract(target).normalize();
            double distance = Math.max(0, target.distanceTo(playerEyes) - 1.0);

            float sx = (float) (target.x - camPos.x);
            float sy = (float) (target.y - camPos.y);
            float sz = (float) (target.z - camPos.z);

            float ex = (float) (target.x - camPos.x + direction.x * distance);
            float ey = (float) (target.y - camPos.y + direction.y * distance);
            float ez = (float) (target.z - camPos.z + direction.z * distance);

            buffer.vertex(viewMatrix, sx, sy, sz).color(r, g, b, ALPHA);
            buffer.vertex(viewMatrix, ex, ey, ez).color(r, g, b, ALPHA);
        }
    }

    /** Distance affichée devant le joueur pour les shiny repérés au radar (suffixe « radar »). */
    private static void drawPositionLabels(WorldRenderContext context, MinecraftClient client, Camera camera,
                                           Vec3d camPos, Vec3d playerEyes, java.util.List<Vec3d> positions,
                                           String icon, int color, int yOffset) {
        if (positions.isEmpty()) return;
        TextRenderer textRenderer = client.textRenderer;
        MatrixStack matrices = context.matrixStack();

        int line = 0;
        for (Vec3d target : positions) {
            double dist = target.distanceTo(playerEyes);
            String label = icon + " " + (int) dist + "m (radar)";

            Vec3d direction2 = target.subtract(playerEyes).normalize();
            Vec3d labelPos = playerEyes.add(direction2.multiply(1.5));

            matrices.push();
            matrices.translate(labelPos.x - camPos.x, labelPos.y - camPos.y, labelPos.z - camPos.z);
            matrices.multiply(camera.getRotation());
            float scale = 0.008f;
            matrices.scale(scale, -scale, scale);

            Matrix4f textMatrix = matrices.peek().getPositionMatrix();
            int textWidth = textRenderer.getWidth(label);
            textRenderer.draw(label, -textWidth / 2f, yOffset + line * 12, color, false,
                textMatrix, client.getBufferBuilders().getEntityVertexConsumers(),
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
            client.getBufferBuilders().getEntityVertexConsumers().draw();

            matrices.pop();
        }
    }

    /**
     * yOffset décale la ligne de texte : les barons s'affichent sous les shiny
     * pour rester lisibles quand un Pokémon cumule les deux.
     */
    private static void drawLabels(WorldRenderContext context, MinecraftClient client, Camera camera,
                                   Vec3d camPos, Vec3d playerEyes, float tickDelta,
                                   Set<PokemonEntity> entities, String icon, int color, int yOffset) {
        if (entities.isEmpty()) return;

        TextRenderer textRenderer = client.textRenderer;
        MatrixStack matrices = context.matrixStack();

        for (PokemonEntity pe : entities) {
            if (pe == null || pe.isRemoved()) continue;

            Vec3d entityCenter = pe.getLerpedPos(tickDelta).add(0, pe.getHeight() / 2.0, 0);
            double dist = entityCenter.distanceTo(playerEyes);
            String label = icon + " " + (int) dist + "m";

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

            // SEE_THROUGH = visible à travers les blocs
            textRenderer.draw(label, -textWidth / 2f, yOffset, color, false,
                textMatrix, client.getBufferBuilders().getEntityVertexConsumers(),
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
            client.getBufferBuilders().getEntityVertexConsumers().draw();

            matrices.pop();
        }
    }
}
