package android.view;

import android.os.Looper;

/**
 * 编译期 stub（@hide 抽象类）：真实实现由设备 boot classloader 提供。
 * 本桩只经 :hidden-api 模块以 compileOnly 供 :app 编译引用，不进 app dex。
 * 禁止加任何逻辑。方法签名必须与框架真类逐字一致（AOSP frameworks/base
 * core/java/android/view/InputEventReceiver.java）：
 *   public InputEventReceiver(InputChannel inputChannel, Looper looper)
 *   protected void onInputEvent(InputEvent event)
 *   public void finishInputEvent(InputEvent event, boolean handled)
 *   public void dispose()  ← 1005 自愈重建用（真类公开方法）
 */
public abstract class InputEventReceiver {

    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
    }

    protected void onInputEvent(InputEvent event) {
    }

    public void finishInputEvent(InputEvent event, boolean handled) {
    }

    public void dispose() {
    }
}
