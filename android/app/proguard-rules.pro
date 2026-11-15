# Die veröffentlichte App wird nur „entrümpelt“: Was aus den Bibliotheken nie benutzt wird, fliegt raus.
# Nichts wird umbenannt und nichts umgebaut – so verhält sich die App genau wie der Test-Build.
-dontobfuscate
-dontoptimize

# Der eigene Code bleibt vollständig erhalten (auch der Anschluss des MP3-Encoders über JNI)
-keep class com.backewatsch.mp3player.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Bibliotheken verweisen auf Klassen, die es auf Android nicht gibt (nur für andere Plattformen) – kein Abbruch deswegen
-ignorewarnings
