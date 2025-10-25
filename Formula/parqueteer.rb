class Parqueteer < Formula
  desc "CLI tool for working with Parquet files - query, inspect, and convert with ease"
  homepage "https://github.com/yusukensanta/parqueteer"
  url "https://github.com/yusukensanta/parqueteer/releases/download/v0.1.5/parqueteer-0.1.5.tgz"
  version "0.1.5"
  sha256 "a68d653e2d202ac6110747140ec4bba9e0c0030df06cfdfb7621a256bf99e3c6" # Will be calculated during release
  license "Apache-2.0"

  # Requires Java 21 or later
  depends_on "openjdk@21"

  def install
    # Remove Windows batch files
    rm_r Dir["bin/*.bat"]

    # Install all files to libexec
    libexec.install Dir["*"]

    # Create symlink for the main binary
    bin.install_symlink libexec/"bin/parqueteer"

    # Set JAVA_HOME to openjdk@21
    bin.env_script_all_files libexec/"bin", JAVA_HOME: Formula["openjdk@21"].opt_prefix
  end

  test do
    # Test that the binary exists and runs
    output = shell_output("#{bin}/parqueteer --version 2>&1")
    assert_match version.to_s, output
  end
end
