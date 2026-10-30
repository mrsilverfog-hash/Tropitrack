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
        if (TropiTrackerClient.isPaused()) return; // pause : aucun faisceau

        Set<PokemonEntity> shinies = TropiTrackerClient.getActiveShinyEntities();
        Set<PokemonEntity> barons  = TropiTrackerClient.getActiveBaronEntities();
        java.util.List<Vec3d> farShinies = RadarDetector.getFarShinyPositions();
        Set<PokemonEntity> tracked = TropiTrackerClient.getActiveTrackedEntities();
        java.util.List<Vec3d> farHunt = RadarDetector.getFarHuntPositions();
        java.util.List<Vec3d> hideouts = new java.util.ArrayList<>();
        // Toutes les cachettes ont la colonne ; seules les shiny ont en plus le faisceau vers le
        // joueur et la distance affichée
        java.util.List<Vec3d> shinyHideouts = new java.util.ArrayList<>();
        for (RustlingDetector.Spot spot : RustlingDetector.getSpots()) {
            hideouts.add(spot.pos());
            if (spot.shiny()) shinyHideouts.add(spot.pos());
        }
        java.util.List<PokemonEntity> autoTargets = AutoCapture.getMarked();
        java.util.Map<Integer, Vec3d> baronBlips = RadarDetector.getBaronBlips();
        if (!baronBlips.isEmpty()) renderBaronMarkers(context, client, baronBlips);
        if (shinies.isEmpty() && barons.isEmpty() && farShinies.isEmpty() && tracked.isEmpty() && farHunt.isEmpty()
                && hideouts.isEmpty() && autoTargets.isEmpty()) return;

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
        appendPositionBeams(buffer, viewMatrix, farHunt, camPos, playerEyes, TRACKED_R, TRACKED_G, TRACKED_B);

        drawIfNotEmpty(buffer);

        // --- Capture auto : petite cible rouge sur chaque Pokémon visé (pas de faisceau) ---
        if (!autoTargets.isEmpty()) {
            RenderSystem.lineWidth(2.5f);
            BufferBuilder reticle = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            appendReticles(reticle, viewMatrix, autoTargets, camera, camPos, tickDelta);
            drawIfNotEmpty(reticle);
            RenderSystem.lineWidth(2.0f);
        }

        // --- Cachettes (rustling spots) : faisceau blanc à contour noir, lisible sur la neige ---
        if (!hideouts.isEmpty()) {
            // Faisceau vers le joueur (cachettes shiny uniquement) : visible à travers les blocs
            if (!shinyHideouts.isEmpty()) {
                RenderSystem.lineWidth(6.0f);
                BufferBuilder outline = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
                appendPositionBeams(outline, viewMatrix, shinyHideouts, camPos, playerEyes, 0f, 0f, 0f);
                drawIfNotEmpty(outline);
                RenderSystem.lineWidth(2.5f);
                BufferBuilder core = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
                appendPositionBeams(core, viewMatrix, shinyHideouts, camPos, playerEyes, 1f, 1f, 1f);
                drawIfNotEmpty(core);
            }

            // Colonnes : PAS visibles à travers les blocs (comme les faisceaux de ChestTracker),
            // et plus foncées (gris) au-dessus du premier obstacle
            RenderSystem.enableDepthTest();
            java.util.List<RustlingDetector.Spot> spots = RustlingDetector.getSpots();
            RenderSystem.lineWidth(6.0f);
            BufferBuilder pillarOutline = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            appendPillars(pillarOutline, viewMatrix, spots, camPos, false);
            drawIfNotEmpty(pillarOutline);
            RenderSystem.lineWidth(2.5f);
            BufferBuilder pillarCore = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            appendPillars(pillarCore, viewMatrix, spots, camPos, true);
            drawIfNotEmpty(pillarCore);
            RenderSystem.disableDepthTest();
            RenderSystem.lineWidth(2.0f);
        }

        // --- Textes de distance flottants devant le joueur, visibles à travers les blocs ---
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, shinies, "\u2728", SHINY_TEXT_COLOR, 0);
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, barons,  "\uD83D\uDC51", BARON_TEXT_COLOR, 12);
        drawPositionLabels(context, client, camera, camPos, playerEyes, farShinies, "\u2728", SHINY_TEXT_COLOR, 24);
        drawLabels(context, client, camera, camPos, playerEyes, tickDelta, tracked, "\uD83C\uDFAF", TRACKED_TEXT_COLOR, 36);
        drawPositionLabels(context, client, camera, camPos, playerEyes, farHunt, "\uD83C\uDFAF", TRACKED_TEXT_COLOR, 48);
        drawOutlinedLabels(context, client, camera, camPos, playerEyes, shinyHideouts, "\uD83C\uDF3F", 60);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /**
     * Dessine le lot seulement s'il contient des sommets : BufferBuilder.end() plante
     * (« BufferBuilder was empty ») quand rien n'a été ajouté, par exemple s'il n'y a que des
     * cachettes à afficher et aucun Pokémon.
     */
    private static void drawIfNotEmpty(BufferBuilder buffer) {
        BuiltBuffer built = buffer.endNullable();
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built);
        }
    }

    /**
     * Barons du radar : losange rouge à contour noir au-dessus du Pokémon (ou de la position du blip
     * s'il n'est pas encore chargé), visible à travers les blocs, taille constante à l'écran.
     */
    private static void renderBaronMarkers(WorldRenderContext context, MinecraftClient client,
                                           java.util.Map<Integer, Vec3d> blips) {
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);
        Matrix4f viewMatrix = context.matrixStack().peek().getPositionMatrix();

        double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
        Vec3d fwd = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d l = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)).normalize();
        Vec3d u = l.crossProduct(fwd).normalize();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        for (java.util.Map.Entry<Integer, Vec3d> e : blips.entrySet()) {
            net.minecraft.entity.Entity ent = client.world.getEntityById(e.getKey());
            Vec3d top;
            if (ent != null && !ent.isRemoved()) {
                top = ent.getLerpedPos(tickDelta).add(0, ent.getHeight() + 0.6, 0);
            } else {
                top = e.getValue().add(0, 1.6, 0);
            }
            Vec3d c = top.subtract(camPos);
            double size = 0.18 + c.length() * 0.018;   // garde à peu près la même taille à l'écran
            appendDiamond(buf, viewMatrix, c, l, u, size * 1.35, 0f, 0f, 0f);        // contour noir
            appendDiamond(buf, viewMatrix, c.subtract(fwd.multiply(0.01)), l, u, size, 1f, 0.1f, 0.1f); // cœur rouge
        }
        drawIfNotEmpty(buf);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void appendDiamond(BufferBuilder buf, Matrix4f m, Vec3d c, Vec3d l, Vec3d u, double r,
                                      float cr, float cg, float cb) {
        Vec3d top = c.add(u.multiply(r)), bottom = c.subtract(u.multiply(r));
        Vec3d left = c.add(l.multiply(r * 0.75)), right = c.subtract(l.multiply(r * 0.75));
        vtx(buf, m, top, cr, cg, cb); vtx(buf, m, left, cr, cg, cb); vtx(buf, m, right, cr, cg, cb);
        vtx(buf, m, bottom, cr, cg, cb); vtx(buf, m, right, cr, cg, cb); vtx(buf, m, left, cr, cg, cb);
    }

    private static void vtx(BufferBuilder buf, Matrix4f m, Vec3d p, float r, float g, float b) {
        buf.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 1f);
    }

    /** Petit réticule (cercle + 4 traits) face à la caméra, centré sur le Pokémon. */
    private static void appendReticles(BufferBuilder buffer, Matrix4f viewMatrix, java.util.List<PokemonEntity> entities,
                                       Camera camera, Vec3d camPos, float tickDelta) {
        // Deux axes perpendiculaires au regard, calculés depuis l'orientation de la caméra
        double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
        Vec3d fwd = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d l = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)).normalize();
        Vec3d u = l.crossProduct(fwd).normalize();
        final float r = 1.0f, g = 0.15f, b = 0.15f;
        final int SEG = 20;
        for (PokemonEntity pe : entities) {
            if (pe == null || pe.isRemoved()) continue;
            Vec3d c = pe.getLerpedPos(tickDelta).add(0, pe.getHeight() / 2.0, 0).subtract(camPos);
            double rad = Math.max(0.25, Math.min(0.6, Math.max(pe.getWidth(), pe.getHeight()) * 0.35));
            for (int i = 0; i < SEG; i++) {
                double a0 = 2 * Math.PI * i / SEG, a1 = 2 * Math.PI * (i + 1) / SEG;
                Vec3d p0 = c.add(l.multiply(Math.cos(a0) * rad)).add(u.multiply(Math.sin(a0) * rad));
                Vec3d p1 = c.add(l.multiply(Math.cos(a1) * rad)).add(u.multiply(Math.sin(a1) * rad));
                buffer.vertex(viewMatrix, (float) p0.x, (float) p0.y, (float) p0.z).color(r, g, b, ALPHA);
                buffer.vertex(viewMatrix, (float) p1.x, (float) p1.y, (float) p1.z).color(r, g, b, ALPHA);
            }
            // 4 traits qui débordent du cercle, comme un viseur
            Vec3d[] dirs = { l, l.multiply(-1), u, u.multiply(-1) };
            for (Vec3d d : dirs) {
                Vec3d in = c.add(d.multiply(rad * 0.55));
                Vec3d out = c.add(d.multiply(rad * 1.35));
                buffer.vertex(viewMatrix, (float) in.x, (float) in.y, (float) in.z).color(r, g, b, ALPHA);
                buffer.vertex(viewMatrix, (float) out.x, (float) out.y, (float) out.z).color(r, g, b, ALPHA);
            }
        }
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

    /**
     * Colonne verticale au-dessus de chaque cachette (60 blocs) : le faisceau vers le joueur se voit
     * mal de près (il pointe vers la caméra), la colonne reste visible sous tous les angles.
     */
    private static void appendPillars(BufferBuilder buffer, Matrix4f viewMatrix, java.util.List<RustlingDetector.Spot> spots,
                                      Vec3d camPos, boolean core) {
        float top = RustlingDetector.PILLAR_HEIGHT;
        for (RustlingDetector.Spot spot : spots) {
            Vec3d target = spot.pos();
            float x = (float) (target.x - camPos.x);
            float z = (float) (target.z - camPos.z);
            float y0 = (float) (target.y - 0.5 - camPos.y);
            float split = Math.max(0f, Math.min(top, spot.darkFrom()));
            // Contour : noir sur toute la hauteur. Cœur : blanc jusqu'au premier obstacle, gris au-dessus.
            float lo = core ? 1f : 0f;
            float hi = core ? 0.45f : 0f;
            if (split > 0) {
                buffer.vertex(viewMatrix, x, y0, z).color(lo, lo, lo, ALPHA);
                buffer.vertex(viewMatrix, x, y0 + split, z).color(lo, lo, lo, ALPHA);
            }
            if (split < top) {
                buffer.vertex(viewMatrix, x, y0 + split, z).color(hi, hi, hi, ALPHA);
                buffer.vertex(viewMatrix, x, y0 + top, z).color(hi, hi, hi, ALPHA);
            }
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

    /** Distance des cachettes : texte blanc entouré de noir (lisible sur la neige comme sur l'herbe). */
    private static void drawOutlinedLabels(WorldRenderContext context, MinecraftClient client, Camera camera,
                                           Vec3d camPos, Vec3d playerEyes, java.util.List<Vec3d> positions,
                                           String icon, int yOffset) {
        if (positions.isEmpty()) return;
        TextRenderer textRenderer = client.textRenderer;
        MatrixStack matrices = context.matrixStack();
        net.minecraft.client.render.VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();

        for (Vec3d target : positions) {
            String label = icon + " " + (int) target.distanceTo(playerEyes) + "m";
            Vec3d labelPos = playerEyes.add(target.subtract(playerEyes).normalize().multiply(1.5));

            matrices.push();
            matrices.translate(labelPos.x - camPos.x, labelPos.y - camPos.y, labelPos.z - camPos.z);
            matrices.multiply(camera.getRotation());
            float scale = 0.008f;
            matrices.scale(scale, -scale, scale);

            Matrix4f textMatrix = matrices.peek().getPositionMatrix();
            float x = -textRenderer.getWidth(label) / 2f;
            // Contour noir : le texte décalé d'un pixel dans les 4 directions, puis le blanc au centre
            int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] o : offsets) {
                textRenderer.draw(label, x + o[0], yOffset + o[1], 0x000000, false, textMatrix, consumers,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
            }
            textRenderer.draw(label, x, yOffset, 0xFFFFFF, false, textMatrix, consumers,
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
            matrices.pop();
        }
        consumers.draw();
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
