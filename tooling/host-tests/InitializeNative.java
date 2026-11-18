/** Initializes the pinned client using the same runtime inputs as a Gradle Test task. */
public final class InitializeNative {
  public static void main(String[] args) throws Exception {
    try {
      Class.forName("org.signal.libsignal.internal.Native", false, ClassLoader.getSystemClassLoader());
    } catch (ClassNotFoundException absent) {
      return;
    }
    Class.forName("org.signal.libsignal.internal.Native");
  }
}
