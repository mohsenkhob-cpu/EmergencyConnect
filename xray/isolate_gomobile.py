"""Keep the Xray binding's JNI and Java glue separate from sing-box."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
replacements = {
    "bind/genjava.go": [('return "go"', 'return "mohsenxraygo"'),
                        ("import go.Seq;", "import mohsenxraygo.Seq;")],
    "cmd/gobind/gen.go": [('filepath.Join("java", "go", javaFile)',
                          'filepath.Join("java", "mohsenxraygo", javaFile)')],
    "bind/java/Seq.java": [("package go;", "package mohsenxraygo;"),
                           ("import go.Universe;", "import mohsenxraygo.Universe;"),
                           ('"gojni"', '"mohsenxrayjni"')],
    "bind/java/context_android.c": [("Java_go_Seq_", "Java_mohsenxraygo_Seq_")],
    "bind/java/seq_android.c.support": [("Java_go_Seq_", "Java_mohsenxraygo_Seq_"),
                                        ("go/Seq", "mohsenxraygo/Seq")],
    "cmd/gomobile/bind_androidapp.go": [("libgojni.so", "libmohsenxrayjni.so")],
}

for name, pairs in replacements.items():
    path = root / name
    source = path.read_text()
    for old, new in pairs:
        if old not in source:
            raise RuntimeError(f"Unexpected gomobile layout at {name}: {old}")
        source = source.replace(old, new)
    path.write_text(source)
