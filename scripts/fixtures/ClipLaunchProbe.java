import dev.iyanz.sourbyclip.Sourbyclip;

/** Exercises the packaged ServerMain boundary in a separate JVM. */
class ClipLaunchProbe {
    public static void main(String[] args) throws Exception {
        var factory = Sourbyclip.class.getDeclaredMethod("generateThread", Object.class,
                String.class, ClassLoader.class);
        factory.setAccessible(true);
        Thread thread = (Thread) factory.invoke(null, new String[]{args[0]},
                Entry.class.getName(), ClipLaunchProbe.class.getClassLoader());
        thread.start();
        thread.join();
    }

    public static class Entry {
        public static void main(String[] args) {
            if (args[0].equals("failure")) throw new IllegalStateException("fixture ServerMain failure");
            System.out.println("fixture ServerMain success");
        }
    }
}
