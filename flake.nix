{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    systems.url = "github:nix-systems/default";
    devenv.url = "github:cachix/devenv";
  };

  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };

  outputs = { self, nixpkgs, devenv, systems, ... } @ inputs:
    let
      forEachSystem = nixpkgs.lib.genAttrs (import systems);
    in
    {
      devShells = forEachSystem
        (system:
          let
            # pkgs = nixpkgs.legacyPackages.${system};
            pkgs = import nixpkgs {
              inherit system;
              # Android SDK components are unfree and require license acceptance
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
              # kotlin propagates its own JRE onto PATH; keep it on the same JDK as the toolchain
              overlays = [
                (final: prev: {
                  kotlin = prev.kotlin.override { jre = final.jdk17; };
                })
              ];
            };
          in
          {
            default = devenv.lib.mkShell {
              inherit inputs pkgs;
              modules = [
                {
                  android = {
                    enable = true;
                    platforms.version = [ "35" "36" ];
                    buildTools.version = [ "36.0.0" ];
                  };

                  languages.java = {
                    enable = true;
                    jdk.package = pkgs.jdk17;
                  };

                  # also provides gradle and kotlin-language-server
                  languages.kotlin.enable = true;

                  enterShell = ''
                    echo "thumbs shell activated!"
                  '';
                }
              ];
            };
          });
    };
}
