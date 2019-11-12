# tagskip

Small and efficient library to skip over metadata tags in MP3 files (IDv3, APE, ...).

tagskip provides a single class, `TagSkipInputStream`, that skips over any metadata tags and only leaves actual MP3 frames.

tagskip uses slf4j for logging.

## Usage

The latest version is: **`0.1.0`**

### Maven

Add to your Maven `pom.xml`:
```xml
<repositories>
  <repository>
    <url>https://maven.dille.cc</url>
  </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>fr.delthas</groupId>
        <artifactId>tagskip</artifactId>
        <version>VERSION</version>
    </dependency>
</dependencies>
```

### Gradle

Add to your `build.gradle`:

```
repositories {
    maven {
        url 'https://maven.dille.cc'
    }
}

dependencies {
    implementation 'fr.delthas:tagskip:VERSION'
}
```

### Gradle (Kotlin build script)

Add to your `build.gradle.kts`:

```
repositories {
    maven {
        url = uri("https://maven.dille.cc")
    }
}

dependencies {
    implementation("fr.delthas:tagskip:VERSION")
}
```

## Status

tagskip has been tested over a very large collection of music files from various sources and should be somewhat robust.

- [x] support efficient skipping on `skip()`
- [x] skip ID3v1/ID3v2 tags
- [x] skip APEv2 tags
- [x] skip dummy Xing/Info/LAME audio frames
- [x] skip zero-byte garbage frames
- [ ] skip dummy Fraunhofer VBRi header audio frames (very rarely used)
- [ ] skip APEv1 tags (very rarely used, hard to detect)
- [ ] support `InputStream#mark()`
