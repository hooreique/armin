{
  description = "Armin Android spoofdpi-ish browser";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.android-nixpkgs.url = "github:tadfisher/android-nixpkgs/stable";

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
          pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
          };
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
                builtins.elem name [ "result" ]
                || pkgs.lib.hasPrefix "result-" name
                || (
                  type == "directory"
                  && builtins.elem name [
                    ".github"
                    ".direnv"
                    ".git"
                    ".gradle"
                    ".idea"
                    ".vscode"
                    "build"
                  ]
                )
                || (
                  type == "regular"
                  && builtins.elem name [
                    "CONTRIBUTING.md"
                    "README.md"
                    "TODO.md"
                  ]
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
            # The pinned nixpkgs eager resolver is not Gradle-9-safe. Exercising the exact build
            # and verification graph records every dependency that offline builds/tests consume.
            gradleUpdateTask = "quality :app:assembleDebug :app:compileDebugAndroidTestKotlin";
            gradleFlags = [
              "-Dorg.gradle.java.home=${javaHome}"
              "-Pandroid.aapt2FromMavenOverride=${aapt2}"
            ];

            preConfigure = ''
              armin_build_home="$TMPDIR/armin-home"
              mkdir -p "$armin_build_home"
              gradleFlagsArray+=("-Duser.home=$armin_build_home")
            '';

            installPhase = ''
              runHook preInstall
              install -Dm755 app/build/outputs/apk/debug/app-debug.apk "$out/apk/armin.apk"
              install -Dm644 LICENSE "$out/share/licenses/armin/Armin-MIT.txt"
              install -m644 LICENSES/*.txt "$out/share/licenses/armin/"
              install -Dm644 NOTICE "$out/share/doc/armin/NOTICE"
              install -Dm644 THIRD_PARTY_NOTICES.md "$out/share/doc/armin/THIRD_PARTY_NOTICES.md"
              install -Dm644 legal/runtime-dependencies.txt "$out/share/doc/armin/runtime-dependencies.txt"
              runHook postInstall
            '';

            meta = {
              description = "Android spoofdpi-ish browser";
              homepage = "https://github.com/hooreique/armin";
              license = with pkgs.lib.licenses; [
                asl20
                boost
                bsd3
                mit
              ];
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
          pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
          };
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
