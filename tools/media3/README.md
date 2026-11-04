# The video library (Media3 / ExoPlayer) for TopShelf

`app/libs/media3.dex` (runs on the TV) and `app/libs/media3-classes.jar` (for compiling against)
are built from source, because this build has no Gradle or Maven access.

Sources: androidx/media tag 1.4.1 (libraries common, decoder, extractor, datasource, database,
container, exoplayer, exoplayer_hls) + google/guava v33.0.0 android flavour (+ futures/failureaccess).

1. `javac -source 8 -target 8 -bootclasspath compile-only-classes:android.jar -cp sun-misc-stub
   -sourcepath <media3 modules>:<guava>:<failureaccess>:stubs -implicit:class -proc:none Entry.java`
   — compiles only what Entry (the classes NativePlayer uses) reaches. `stubs/` holds the
   annotation types plus tiny stand-ins for androidx.collection.CircularIntArray and
   androidx.exifinterface ExifInterface. `compile-only/` (LambdaMetafactory, sun.misc.Unsafe)
   must never be dexed.
2. Delete the annotation stub classes and Entry from the output; `python3 strip.py <dir>` removes
   MethodParameters attributes (the old D8 can't read them).
3. D8 (desugars lambdas): the only D8 reachable here is the dexed `d8-termux` npm package (D8 1.0.35),
   converted back to JVM classes with google/enjarify (+ enjarify-signed-const.patch), class
   versions bumped to 52, and `cp.py` turning Methodrefs to interfaces into InterfaceMethodrefs.
   Run with `-Xss256m -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal`:
   `D8 --release --min-api 26 --lib android.jar --output out <classes>`.
4. Checked by extracting a test MPEG-TS file, Guava and an HLS playlist on the JVM, both from javac's
   classes and from D8's dex converted back — identical output.
