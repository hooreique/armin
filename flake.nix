{
  description = "Armin Android SpoofDPI WebView browser";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs/stable";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
    }:
    let
      supportedSystems = [ "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in
    {
      packages = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          androidSdk = android-nixpkgs.sdk.${system} (
            sdkPkgs: with sdkPkgs; [
              build-tools-37-0-0
              cmdline-tools-22-0
              platform-tools
              platforms-android-37-0
            ]
          );
          androidHome = "${androidSdk}/share/android-sdk";
          aapt2 = "${androidHome}/build-tools/37.0.0/aapt2";
          gradle = pkgs.gradle_9;
          java = pkgs.jdk17;
          javaHome = java.home;
          kotlinLsp = pkgs.callPackage ./nix/kotlin-lsp.nix { };
          ktfmt = pkgs.stdenvNoCC.mkDerivation {
            pname = "ktfmt";
            version = "0.64";
            src = pkgs.fetchurl {
              url = "https://github.com/Kotlin/ktfmt/releases/download/v0.64/ktfmt-0.64-with-dependencies.jar";
              hash = "sha256-uPu4FICNjaM/dKe7rLbRdIzvgcAgKn+Cm4cTlSC1EnM=";
            };
            dontUnpack = true;
            nativeBuildInputs = [ pkgs.makeWrapper ];
            installPhase = ''
              runHook preInstall
              install -Dm644 "$src" "$out/share/ktfmt/ktfmt.jar"
              makeWrapper ${java}/bin/java "$out/bin/ktfmt" \
                --add-flags "-jar $out/share/ktfmt/ktfmt.jar"
              runHook postInstall
            '';
            meta = {
              description = "Kotlin code formatter based on google-java-format";
              homepage = "https://github.com/Kotlin/ktfmt";
              license = pkgs.lib.licenses.asl20;
              mainProgram = "ktfmt";
            };
          };
          cleanSource = pkgs.lib.cleanSourceWith {
            src = ./.;
            filter =
              path: type:
              let
                name = baseNameOf path;
              in
              !(
                type == "directory"
                && (
                  builtins.elem name [
                    ".direnv"
                    ".git"
                    ".gradle"
                    ".idea"
                    ".vscode"
                    "build"
                    "result"
                  ]
                  || pkgs.lib.hasPrefix "result-" name
                )
              );
          };
          armin = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
            pname = "armin";
            version = "0.1.0";
            src = cleanSource;

            nativeBuildInputs = [
              androidSdk
              gradle
              java
            ];

            env = {
              ANDROID_HOME = androidHome;
              ANDROID_SDK_ROOT = androidHome;
              JAVA_HOME = javaHome;
            };

            mitmCache = gradle.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./nix/gradle-deps.json;
            };

            gradleBuildTask = "quality :app:assembleDebug";
            gradleUpdateTask = "nixDownloadDeps";
            gradleFlags = [
              "-Dorg.gradle.java.home=${javaHome}"
              "-Pandroid.aapt2FromMavenOverride=${aapt2}"
            ];

            preConfigure = ''
              export HOME="$TMPDIR/home"
              mkdir -p "$HOME"
            '';

            installPhase = ''
              runHook preInstall
              install -Dm755 app/build/outputs/apk/debug/app-debug.apk "$out/apk/armin.apk"
              runHook postInstall
            '';

            meta = {
              description = "Android SpoofDPI WebView browser";
              platforms = supportedSystems;
            };
          });
        in
        {
          default = armin;
          inherit
            androidSdk
            armin
            kotlinLsp
            ktfmt
            ;
        }
      );

      checks = forAllSystems (system: {
        default = self.packages.${system}.armin;
      });

      apps = forAllSystems (system: {
        update-deps = {
          type = "app";
          program = "${self.packages.${system}.armin.mitmCache.updateScript}";
        };
      });

      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          androidSdk = self.packages.${system}.androidSdk;
          androidHome = "${androidSdk}/share/android-sdk";
          java = pkgs.jdk17;
          javaHome = java.home;
          kotlinLsp = self.packages.${system}.kotlinLsp;
        in
        {
          default = pkgs.mkShell {
            packages = [
              androidSdk
              java
              pkgs.gradle_9
              self.packages.${system}.ktfmt
              kotlinLsp
            ];

            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;
            JAVA_HOME = javaHome;
            KOTLIN_LSP_DIR = "${kotlinLsp}/libexec/kotlin-lsp";

            shellHook = ''
              export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/37.0.0/aapt2"
            '';
          };
        }
      );

      formatter = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          treefmtConfig = pkgs.writeText "treefmt.toml" ''
            [formatter.nixfmt]
            command = "${pkgs.lib.getExe pkgs.nixfmt}"
            includes = ["*.nix"]

            [formatter.ktfmt]
            command = "${pkgs.lib.getExe self.packages.${system}.ktfmt}"
            options = ["--kotlinlang-style"]
            includes = ["*.kt", "*.kts"]
            excludes = ["**/.gradle/**", "**/build/**"]
          '';
        in
        pkgs.writeShellApplication {
          name = "armin-format";
          runtimeInputs = [ pkgs.treefmt ];
          text = ''
            tree_root=.
            if [[ $# -eq 1 && -d "$1" ]]; then
              tree_root=$1
              shift
            fi
            exec treefmt --tree-root "$tree_root" --config-file ${treefmtConfig} "$@"
          '';
        }
      );
    };
}
