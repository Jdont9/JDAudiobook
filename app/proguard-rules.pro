# R8 / release. Media3, Compose et AndroidX fournissent leurs propres règles « consumer ».
# Pas d'obfuscation : projet libre (GPL), et les traces d'erreur restent lisibles.
-dontobfuscate

# Annotations de Guava absentes à l'exécution (dépendances « compile-only ») : sans effet, on silence l'avertissement.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**
