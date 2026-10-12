package net.catgirl.client;

/** Public doorway for the mouse-wheel mixin. */
public final class ZoomHooks {
    private ZoomHooks() {}
    public static boolean scroll(double amount) { return Zoom.scroll(amount); }
}
