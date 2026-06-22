package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferRenderer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormats;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public class ShinyBeamRenderer {

    // Couleur dorée du faisceau (0.0 - 1.0)
    private static final float R = 1.0f;
    private static final float G = 0.85f;
    private static final float B = 0.1f;
    private static final float ALPHA_BAS  = 0.6f; // opacité en bas, près du Pokémon
    private static final float ALPHA_HAUT = 0.0f; // s'estompe en montant

    private static final double BEAM_HEIGHT = 200.0; // hauteur du faisceau (en blocs)
    private static final double HALF_WIDTH  = 0.25;  // épaisseur du faisceau

    public static void render(WorldRenderContext context) {
        var shinies = TropiTrackerClient.getActiveShinyEntities();
        if (shinies.isEmpty()) return;

        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        for (PokemonEntity pe : shinies) {
            if (pe == null || pe.isRemoved()) continue;

            Vec3d pos = pe.getLerpedPos(tickDelta);
            double relX = pos.x - camPos.x;
            double relY = pos.y - camPos.y;
            double relZ = pos.z - camPos.z;

            MatrixStack matrices = new MatrixStack();
            matrices.translate(relX, relY, relZ);
            drawBeam(matrices);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void drawBeam(MatrixStack matrices) {
        Matrix4f model = matrices.peek().getPositionMatrix();

        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        // Deux plans verticaux croisés à 90° pour que le faisceau soit visible depuis n'importe quel angle
        addQuad(buffer, model, -HALF_WIDTH, 0,  HALF_WIDTH, 0);
        addQuad(buffer, model, 0, -HALF_WIDTH, 0,  HALF_WIDTH);

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    // Dessine un quad vertical de y=0 (sol, près du Pokémon) à y=BEAM_HEIGHT (ciel),
    // entre deux points horizontaux (x1,z1) et (x2,z2)
    private static void addQuad(BufferBuilder buffer, Matrix4f model, double x1, double z1, double x2, double z2) {
        buffer.vertex(model, (float) x1, 0f, (float) z1).color(R, G, B, ALPHA_BAS);
        buffer.vertex(model, (float) x2, 0f, (float) z2).color(R, G, B, ALPHA_BAS);
        buffer.vertex(model, (float) x2, (float) BEAM_HEIGHT, (float) z2).color(R, G, B, ALPHA_HAUT);
        buffer.vertex(model, (float) x1, (float) BEAM_HEIGHT, (float) z1).color(R, G, B, ALPHA_HAUT);
    }
}
