package android.view;

import android.os.Looper;

/**
 * 编译期 stub（@hide 类）：方法签名必须与框架真实类逐字一致——运行时本类被 boot
 * classloader 的真类顶替，本模块的子类实际继承真类。禁止加任何逻辑。
 * 真类签名（AOSP frameworks/base/core/java/android/view/InputEventReceiver.java）：
 *   public InputEventReceiver(InputChannel inputChannel, Looper looper)
 *   protected void onInputEvent(InputEvent event)
 *   public void finishInputEvent(InputEvent event, boolean handled)
 */
public abstract class InputEventReceiver {

    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
    }

    protected void onInputEvent(InputEvent event) {
    }

    public void finishInputEvent(InputEvent event, boolean handled) {
    }
}
