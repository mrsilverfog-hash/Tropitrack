package net.tropimon.tropitracker.mixin;

import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.tropimon.tropitracker.CatchDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Lit le texte des notifications « toast » (vanilla ou ajoutées par un autre mod)
 * et le transmet au CatchDetector. Le texte est récupéré par réflexion sur tous
 * les champs Text / OrderedText / listes, car chaque mod range ça à sa façon.
 */
@Mixin(ToastManager.class)
public class ToastManagerMixin {

    @Inject(method = "add", at = @At("HEAD"), require = 0)
    private void tropitracker$onToast(Toast toast, CallbackInfo ci) {
        try {
            if (toast == null) return;
            StringBuilder sb = new StringBuilder();
            Class<?> cls = toast.getClass();
            int depth = 0;
            while (cls != null && cls != Object.class && depth < 4) {
                for (Field f : cls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        tropitracker$append(sb, f.get(toast));
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
                depth++;
            }
            if (sb.length() > 0) {
                CatchDetector.onHudString(sb.toString().trim(), "toast");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tropitracker$append(StringBuilder sb, Object value) {
        if (value == null) return;
        if (value instanceof Text t) {
            sb.append(t.getString()).append(' ');
        } else if (value instanceof OrderedText o) {
            o.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
            sb.append(' ');
        } else if (value instanceof Iterable<?> it) {
            int n = 0;
            for (Object o : it) {
                if (n++ > 10) break;
                if (o instanceof Text || o instanceof OrderedText) tropitracker$append(sb, o);
            }
        }
    }
}
