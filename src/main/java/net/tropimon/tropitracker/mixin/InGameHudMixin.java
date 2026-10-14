package net.tropimon.tropitracker.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import net.tropimon.tropitracker.CatchDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Transmet au CatchDetector tout ce qui s'affiche en titre, sous-titre
 * et barre d'action. require = 0 : si une méthode change de nom, le mod
 * se charge quand même au lieu de faire planter le jeu.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "setTitle", at = @At("HEAD"), require = 0)
    private void tropitracker$onTitle(Text title, CallbackInfo ci) {
        CatchDetector.onHudText(title, "title");
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), require = 0)
    private void tropitracker$onSubtitle(Text subtitle, CallbackInfo ci) {
        CatchDetector.onHudText(subtitle, "subtitle");
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), require = 0)
    private void tropitracker$onOverlay(Text message, boolean tinted, CallbackInfo ci) {
        CatchDetector.onHudText(message, "actionbar");
    }
}
