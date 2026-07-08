#!/usr/bin/env bash
# Parqueteer installation script
# Usage: curl -fsSL https://raw.githubusercontent.com/yusukensanta/parqueteer/main/scripts/install.sh | bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Configuration
REPO="yusukensanta/parqueteer"
INSTALL_DIR="${PARQUETEER_INSTALL_DIR:-$HOME/.parqueteer}"
BIN_DIR="${PARQUETEER_BIN_DIR:-$HOME/.local/bin}"

# Detect OS and architecture
detect_platform() {
    local os=$(uname -s | tr '[:upper:]' '[:lower:]')
    local arch=$(uname -m)

    case "$os" in
        linux*)
            OS="linux"
            ;;
        darwin*)
            OS="macos"
            ;;
        msys*|mingw*|cygwin*)
            OS="windows"
            ;;
        *)
            echo -e "${RED}✗${NC} Unsupported operating system: $os"
            exit 1
            ;;
    esac

    case "$arch" in
        x86_64|amd64)
            ARCH="x86_64"
            ;;
        aarch64|arm64)
            ARCH="aarch64"
            ;;
        *)
            echo -e "${YELLOW}⚠${NC}  Unsupported architecture: $arch (attempting x86_64)"
            ARCH="x86_64"
            ;;
    esac

    echo -e "${BLUE}Detected platform:${NC} $OS ($ARCH)"
}

# Check dependencies
check_dependencies() {
    local missing_deps=()

    # Check for required commands
    for cmd in curl tar java; do
        if ! command -v $cmd &> /dev/null; then
            missing_deps+=($cmd)
        fi
    done

    if [ ${#missing_deps[@]} -ne 0 ]; then
        echo -e "${RED}✗${NC} Missing required dependencies: ${missing_deps[*]}"
        echo ""
        echo "Please install the missing dependencies:"
        echo ""
        if [[ " ${missing_deps[*]} " =~ " java " ]]; then
            echo "  Java 21+:"
            echo "    macOS:   brew install openjdk@21"
            echo "    Ubuntu:  sudo apt install openjdk-21-jdk"
            echo "    Fedora:  sudo dnf install java-21-openjdk"
        fi
        if [[ " ${missing_deps[*]} " =~ " curl " ]]; then
            echo "  curl:    Usually pre-installed, check your package manager"
        fi
        if [[ " ${missing_deps[*]} " =~ " tar " ]]; then
            echo "  tar:     Usually pre-installed, check your package manager"
        fi
        exit 1
    fi

    # Check Java version
    local java_version=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
    if [ "$java_version" -lt 17 ]; then
        echo -e "${YELLOW}⚠${NC}  Warning: Java $java_version detected. Parqueteer requires Java 17+"
        echo "    Attempting to continue, but you may encounter issues..."
    else
        echo -e "${GREEN}✓${NC} Java $java_version detected"
    fi
}

# Get latest release version
get_latest_version() {
    echo -e "${BLUE}Fetching latest release...${NC}"

    # Try to get latest release from GitHub API
    local latest=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')

    if [ -z "$latest" ]; then
        echo -e "${RED}✗${NC} Failed to fetch latest release"
        exit 1
    fi

    VERSION="${latest#v}" # Remove 'v' prefix
    echo -e "${GREEN}✓${NC} Latest version: $VERSION"
}

# Download and extract
download_and_extract() {
    local download_url="https://github.com/$REPO/releases/download/v${VERSION}/parqueteer-${VERSION}.tgz"
    local tmp_dir=$(mktemp -d)
    local tmp_file="$tmp_dir/parqueteer.tgz"

    echo -e "${BLUE}Downloading parqueteer ${VERSION}...${NC}"

    if ! curl -fL --progress-bar "$download_url" -o "$tmp_file"; then
        echo -e "${RED}✗${NC} Download failed"
        rm -rf "$tmp_dir"
        exit 1
    fi

    echo -e "${GREEN}✓${NC} Downloaded successfully"

    echo -e "${BLUE}Extracting to $INSTALL_DIR...${NC}"

    # Remove old installation if exists
    if [ -d "$INSTALL_DIR" ]; then
        echo -e "${YELLOW}⚠${NC}  Removing previous installation..."
        rm -rf "$INSTALL_DIR"
    fi

    # Create installation directory
    mkdir -p "$INSTALL_DIR"

    # Extract
    tar -xzf "$tmp_file" -C "$INSTALL_DIR" --strip-components=1

    # Cleanup
    rm -rf "$tmp_dir"

    echo -e "${GREEN}✓${NC} Extracted successfully"
}

# Create symlink
create_symlink() {
    echo -e "${BLUE}Creating symlink...${NC}"

    # Create bin directory if it doesn't exist
    mkdir -p "$BIN_DIR"

    # Remove old symlink if exists
    if [ -L "$BIN_DIR/parqueteer" ] || [ -f "$BIN_DIR/parqueteer" ]; then
        rm -f "$BIN_DIR/parqueteer"
    fi

    # Create symlink
    ln -s "$INSTALL_DIR/bin/parqueteer" "$BIN_DIR/parqueteer"

    # Make executable
    chmod +x "$INSTALL_DIR/bin/parqueteer"

    echo -e "${GREEN}✓${NC} Symlink created at $BIN_DIR/parqueteer"
}

# Check if bin directory is in PATH
check_path() {
    if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
        echo ""
        echo -e "${YELLOW}⚠${NC}  $BIN_DIR is not in your PATH"
        echo ""
        echo "Add the following to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
        echo ""
        echo -e "  ${GREEN}export PATH=\"\$PATH:$BIN_DIR\"${NC}"
        echo ""
        echo "Then reload your shell:"
        echo -e "  ${GREEN}source ~/.bashrc${NC}  # or ~/.zshrc"
        echo ""
        NEEDS_PATH_UPDATE=1
    fi
}

# Print success message
print_success() {
    echo ""
    echo -e "${BOLD}${GREEN}🎉 Parqueteer ${VERSION} installed successfully!${NC}"
    echo ""
    echo "Installed to: $INSTALL_DIR"
    echo "Binary: $BIN_DIR/parqueteer"
    echo ""

    if [ -n "$NEEDS_PATH_UPDATE" ]; then
        echo "After updating your PATH, try:"
    else
        echo "Try it out:"
    fi

    echo -e "  ${GREEN}parqueteer --help${NC}"
    echo -e "  ${GREEN}parqueteer --version${NC}"
    echo ""
    echo "Documentation: https://github.com/$REPO"
    echo ""
}

# Main installation flow
main() {
    echo ""
    echo -e "${BOLD}${BLUE}Parqueteer Installer${NC}"
    echo ""

    detect_platform
    check_dependencies
    get_latest_version
    download_and_extract
    create_symlink
    check_path
    print_success
}

# Run main function
main
