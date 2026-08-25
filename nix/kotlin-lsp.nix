{
  autoPatchelfHook,
  fetchurl,
  jdk25,
  lib,
  makeWrapper,
  stdenv,
  stdenvNoCC,
  zlib,
}:

let
  version = "262.9593.0";
  source =
    {
      x86_64-linux = {
        suffix = "";
        hash = "sha256-LZnY4Zj75KqPRIHjd5lyTOlIA7TqEqYLQWBA4/zXzF4=";
      };
      aarch64-linux = {
        suffix = "-aarch64";
        hash = "sha256-IxeDHG5WB9BbfrwdplUzASXODj1m+/JFF9/ORC3rwU4=";
      };
    }
    .${stdenvNoCC.hostPlatform.system};
in
stdenvNoCC.mkDerivation {
  pname = "kotlin-lsp";
  inherit version;

  src = fetchurl {
    url = "https://download-cdn.jetbrains.com/language-server/kotlin-server/${version}/kotlin-server-${version}${source.suffix}.tar.gz";
    inherit (source) hash;
  };

  nativeBuildInputs = [
    autoPatchelfHook
    makeWrapper
  ];

  buildInputs = [
    stdenv.cc.cc.lib
    zlib
  ];

  # The archive bundles an unpatched generic-Linux JBR. The launcher falls
  # back to JAVA_HOME when it is absent; use nixpkgs' pinned JDK 25 instead.
  postPatch = ''
    rm -rf jbr
  '';

  installPhase = ''
    runHook preInstall

    mkdir -p "$out/bin" "$out/libexec/kotlin-lsp"
    cp -R . "$out/libexec/kotlin-lsp"
    for launcher in kotlin-lsp intellij-server; do
      makeWrapper "$out/libexec/kotlin-lsp/bin/intellij-server" "$out/bin/$launcher" \
        --set JAVA_HOME ${jdk25.home} \
        --prefix PATH : ${lib.makeBinPath [ jdk25 ]}
    done

    runHook postInstall
  '';

  meta = {
    description = "Official Kotlin language server by JetBrains";
    homepage = "https://github.com/Kotlin/kotlin-lsp";
    # The source is Apache-2.0; the official standalone archive also bundles
    # JetBrains components distributed under non-free terms.
    license = [
      lib.licenses.asl20
      lib.licenses.unfreeRedistributable
    ];
    mainProgram = "kotlin-lsp";
    platforms = [
      "aarch64-linux"
      "x86_64-linux"
    ];
    sourceProvenance = with lib.sourceTypes; [
      binaryBytecode
      binaryNativeCode
    ];
  };
}
