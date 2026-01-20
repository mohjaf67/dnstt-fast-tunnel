#!/bin/bash

# dnstt-fast Server Setup Script
# https://github.com/mohjaf67/dnstt-fast-tunnel

# Reopen stdin from terminal (needed for curl download + run)
exec < /dev/tty

# Check if running as root
if [[ $EUID -ne 0 ]]; then
    echo -e "\033[0;31m[ERROR]\033[0m This script must be run as root"
    exit 1
fi

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[1;36m'
WHITE='\033[1;37m'
NC='\033[0m'

# Global variables
GITHUB_REPO="mohjaf67/dnstt-fast-tunnel"
GITHUB_RAW="https://raw.githubusercontent.com/$GITHUB_REPO/main"
GITHUB_RELEASES="https://github.com/$GITHUB_REPO/releases"
SCRIPT_URL="$GITHUB_RAW/dnstt-deploy.sh"
INSTALL_DIR="/usr/local/bin"
CONFIG_DIR="/etc/dnstt"
DNSTT_PORT="5300"
DNSTT_USER="dnstt"
CONFIG_FILE="$CONFIG_DIR/dnstt.conf"
PRIVATE_KEY_FILE="$CONFIG_DIR/server.key"
PUBLIC_KEY_FILE="$CONFIG_DIR/server.pub"
SCRIPT_INSTALL_PATH="/usr/local/bin/dnstt-deploy"
SERVICE_NAME="dnstt-server"
UPDATE_AVAILABLE=false

# ==================== Output Functions ====================

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_question() {
    echo -ne "${BLUE}[?]${NC} $1"
}

print_header() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${WHITE}              dnstt-fast Server Manager                       ${CYAN}║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_success_box() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║              SETUP COMPLETED SUCCESSFULLY!                   ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${CYAN}Configuration Details:${NC}"
    echo -e "  Subdomain:    ${YELLOW}$NS_SUBDOMAIN${NC}"
    echo -e "  MTU:          ${YELLOW}$MTU_VALUE${NC}"
    echo -e "  Tunnel mode:  ${YELLOW}$TUNNEL_MODE${NC}"
    echo -e "  Listen port:  ${YELLOW}$DNSTT_PORT${NC} (redirected from 53)"
    echo ""
    echo -e "${CYAN}Public Key:${NC}"
    echo -e "${YELLOW}$(cat "$PUBLIC_KEY_FILE")${NC}"
    echo ""
    echo -e "${CYAN}Management Commands:${NC}"
    echo -e "  Menu:     ${WHITE}dnstt-deploy${NC}"
    echo -e "  Status:   ${WHITE}systemctl status $SERVICE_NAME${NC}"
    echo -e "  Logs:     ${WHITE}journalctl -u $SERVICE_NAME -f${NC}"
    echo -e "  Restart:  ${WHITE}systemctl restart $SERVICE_NAME${NC}"

    if [ "$TUNNEL_MODE" = "socks" ]; then
        echo ""
        echo -e "${CYAN}SOCKS Proxy:${NC}"
        echo -e "  Running on ${YELLOW}127.0.0.1:1080${NC}"
        echo -e "  Status:   ${WHITE}systemctl status danted${NC}"
    fi
    echo ""
}

# ==================== System Detection ====================

detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS=$ID
    else
        print_error "Cannot detect OS"
        exit 1
    fi

    if command -v dnf &>/dev/null; then
        PKG_MANAGER="dnf"
    elif command -v yum &>/dev/null; then
        PKG_MANAGER="yum"
    elif command -v apt-get &>/dev/null; then
        PKG_MANAGER="apt"
    else
        print_error "Unsupported package manager"
        exit 1
    fi

    print_status "Detected OS: $OS ($PKG_MANAGER)"
}

detect_arch() {
    local arch
    arch=$(uname -m)
    case $arch in
        x86_64)     ARCH="amd64" ;;
        aarch64)    ARCH="arm64" ;;
        armv7l)     ARCH="arm" ;;
        i386|i686)  ARCH="386" ;;
        *)
            print_error "Unsupported architecture: $arch"
            exit 1
            ;;
    esac
    print_status "Detected architecture: $ARCH"
}

get_default_interface() {
    ip route | grep default | awk '{print $5}' | head -1
}

get_ssh_port() {
    ss -tlnp | grep sshd | awk '{print $4}' | grep -oE '[0-9]+$' | head -1
}

# ==================== Configuration ====================

load_config() {
    if [ -f "$CONFIG_FILE" ]; then
        source "$CONFIG_FILE"
        return 0
    fi
    return 1
}

save_config() {
    mkdir -p "$CONFIG_DIR"
    cat > "$CONFIG_FILE" << EOF
# dnstt-fast Server Configuration
# Generated on $(date)

NS_SUBDOMAIN="$NS_SUBDOMAIN"
MTU_VALUE="$MTU_VALUE"
TUNNEL_MODE="$TUNNEL_MODE"
PRIVATE_KEY_FILE="$PRIVATE_KEY_FILE"
PUBLIC_KEY_FILE="$PUBLIC_KEY_FILE"
EOF
    chmod 640 "$CONFIG_FILE"
    print_status "Configuration saved"
}

# ==================== Installation ====================

install_dependencies() {
    print_status "Installing dependencies..."
    case "$PKG_MANAGER" in
        apt)
            apt-get update -qq
            apt-get install -y -qq curl iptables iptables-persistent
            ;;
        dnf|yum)
            $PKG_MANAGER install -y -q curl iptables iptables-services
            ;;
    esac
}

create_user() {
    if ! id "$DNSTT_USER" &>/dev/null; then
        useradd -r -s /bin/false "$DNSTT_USER"
        print_status "Created user: $DNSTT_USER"
    fi
}

get_latest_release() {
    curl -sL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | \
        grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/'
}

download_binary() {
    local version="$1"
    local binary_name="dnstt-server-linux-$ARCH"
    local download_url="https://github.com/${GITHUB_REPO}/releases/download/${version}/${binary_name}"
    local temp_file="/tmp/dnstt-server-$$"

    print_status "Downloading $binary_name ($version)..."
    print_status "From: $download_url"

    if curl -fSL "$download_url" -o "$temp_file" 2>&1; then
        if [ -s "$temp_file" ]; then
            mv "$temp_file" "$INSTALL_DIR/dnstt-server"
            chmod +x "$INSTALL_DIR/dnstt-server"
            print_status "Binary installed to $INSTALL_DIR/dnstt-server"
            return 0
        fi
    fi

    print_error "Failed to download binary"
    rm -f "$temp_file"
    return 1
}

install_binary() {
    local version
    version=$(get_latest_release)

    if [ -z "$version" ]; then
        print_error "Could not fetch latest release version"
        return 1
    fi

    print_status "Latest release: $version"
    download_binary "$version"
}

generate_keys() {
    if [ ! -f "$PRIVATE_KEY_FILE" ]; then
        print_status "Generating keypair..."
        "$INSTALL_DIR/dnstt-server" -gen-key -privkey-file "$PRIVATE_KEY_FILE" -pubkey-file "$PUBLIC_KEY_FILE"
        chown "$DNSTT_USER:$DNSTT_USER" "$PRIVATE_KEY_FILE" "$PUBLIC_KEY_FILE"
        chmod 600 "$PRIVATE_KEY_FILE"
        chmod 644 "$PUBLIC_KEY_FILE"
        print_status "Keypair generated"
    else
        print_status "Using existing keypair"
    fi
}

# ==================== Network Configuration ====================

configure_iptables() {
    print_status "Configuring iptables (53 -> $DNSTT_PORT)..."

    # Remove existing rules
    iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-port $DNSTT_PORT 2>/dev/null || true
    ip6tables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-port $DNSTT_PORT 2>/dev/null || true

    # Add new rules
    iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-port $DNSTT_PORT

    # IPv6 if supported
    if [ -f /proc/net/if_inet6 ] && command -v ip6tables &>/dev/null; then
        ip6tables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-port $DNSTT_PORT 2>/dev/null || true
    fi

    # Save rules
    if [ "$PKG_MANAGER" = "apt" ]; then
        netfilter-persistent save 2>/dev/null || iptables-save > /etc/iptables/rules.v4
    else
        service iptables save 2>/dev/null || true
    fi

    # Handle firewalld
    if systemctl is-active --quiet firewalld; then
        firewall-cmd --permanent --add-port=53/udp 2>/dev/null || true
        firewall-cmd --reload 2>/dev/null || true
    fi

    print_status "iptables configured"
}

# ==================== SOCKS Proxy ====================

install_dante() {
    print_status "Installing Dante SOCKS proxy..."

    case "$PKG_MANAGER" in
        apt)
            apt-get install -y -qq dante-server
            ;;
        dnf|yum)
            $PKG_MANAGER install -y -q dante-server 2>/dev/null || {
                print_warning "Dante not available in repos, skipping"
                return 1
            }
            ;;
    esac

    local interface
    interface=$(get_default_interface)

    cat > /etc/danted.conf << EOF
logoutput: syslog
internal: 127.0.0.1 port = 1080
external: $interface

socksmethod: none
clientmethod: none

client pass {
    from: 127.0.0.0/8 to: 0.0.0.0/0
    log: error
}

socks pass {
    from: 127.0.0.0/8 to: 0.0.0.0/0
    log: error
}
EOF

    systemctl enable danted
    systemctl restart danted
    print_status "Dante running on 127.0.0.1:1080"
}

# ==================== Systemd Service ====================

create_service() {
    local upstream

    if [ "$TUNNEL_MODE" = "socks" ]; then
        upstream="127.0.0.1:1080"
    else
        local ssh_port
        ssh_port=$(get_ssh_port)
        upstream="127.0.0.1:${ssh_port:-22}"
    fi

    cat > "/etc/systemd/system/$SERVICE_NAME.service" << EOF
[Unit]
Description=dnstt-fast DNS Tunnel Server
After=network.target

[Service]
Type=simple
User=$DNSTT_USER
Group=$DNSTT_USER
ExecStart=$INSTALL_DIR/dnstt-server -zstd -udp :$DNSTT_PORT -privkey-file $PRIVATE_KEY_FILE -mtu $MTU_VALUE $NS_SUBDOMAIN $upstream
Restart=always
RestartSec=5
LimitNOFILE=65535

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadOnlyPaths=/
ReadWritePaths=$CONFIG_DIR

AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    print_status "Systemd service created"
}

start_service() {
    systemctl enable "$SERVICE_NAME"
    systemctl restart "$SERVICE_NAME"

    sleep 2
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        print_status "Service started successfully"
        return 0
    else
        print_error "Service failed to start"
        journalctl -u "$SERVICE_NAME" -n 10 --no-pager
        return 1
    fi
}

# ==================== Script Self-Management ====================

install_script() {
    print_status "Installing dnstt-deploy script..."

    local temp_script="/tmp/dnstt-deploy-new.sh"

    if ! curl -sL "$SCRIPT_URL" -o "$temp_script"; then
        print_error "Failed to download script"
        return 1
    fi

    chmod +x "$temp_script"

    if [ -f "$SCRIPT_INSTALL_PATH" ]; then
        local current_checksum new_checksum
        current_checksum=$(sha256sum "$SCRIPT_INSTALL_PATH" 2>/dev/null | cut -d' ' -f1)
        new_checksum=$(sha256sum "$temp_script" | cut -d' ' -f1)

        if [ "$current_checksum" = "$new_checksum" ]; then
            print_status "Script is already up to date"
            rm "$temp_script"
            return 0
        fi
    fi

    cp "$temp_script" "$SCRIPT_INSTALL_PATH"
    rm "$temp_script"
    print_status "Script installed to $SCRIPT_INSTALL_PATH"
}

check_for_updates() {
    if [ "$0" != "$SCRIPT_INSTALL_PATH" ]; then
        return
    fi

    local temp_script="/tmp/dnstt-deploy-check.sh"
    if curl -sL "$SCRIPT_URL" -o "$temp_script" 2>/dev/null; then
        local current_checksum latest_checksum
        current_checksum=$(sha256sum "$SCRIPT_INSTALL_PATH" | cut -d' ' -f1)
        latest_checksum=$(sha256sum "$temp_script" | cut -d' ' -f1)

        if [ "$current_checksum" != "$latest_checksum" ]; then
            UPDATE_AVAILABLE=true
        fi
        rm -f "$temp_script"
    fi
}

update_script() {
    print_status "Checking for script updates..."

    local temp_script="/tmp/dnstt-deploy-latest.sh"
    if ! curl -sL "$SCRIPT_URL" -o "$temp_script"; then
        print_error "Failed to download latest version"
        return 1
    fi

    local current_checksum latest_checksum
    current_checksum=$(sha256sum "$SCRIPT_INSTALL_PATH" 2>/dev/null | cut -d' ' -f1)
    latest_checksum=$(sha256sum "$temp_script" | cut -d' ' -f1)

    if [ "$current_checksum" = "$latest_checksum" ]; then
        print_status "Already running the latest version"
        rm "$temp_script"
        return 0
    fi

    print_status "New version found, updating..."
    chmod +x "$temp_script"
    cp "$temp_script" "$SCRIPT_INSTALL_PATH"
    rm "$temp_script"
    print_status "Script updated! Restarting..."
    exec "$SCRIPT_INSTALL_PATH"
}

update_binary() {
    print_status "Checking for binary updates..."

    detect_arch

    if [ -f "$INSTALL_DIR/dnstt-server" ]; then
        cp "$INSTALL_DIR/dnstt-server" "$INSTALL_DIR/dnstt-server.bak"
    fi

    if install_binary; then
        systemctl restart "$SERVICE_NAME" 2>/dev/null || true

        if systemctl is-active --quiet "$SERVICE_NAME"; then
            print_status "Binary updated successfully"
            rm -f "$INSTALL_DIR/dnstt-server.bak"
        else
            print_warning "Service not running after update, rolling back..."
            mv "$INSTALL_DIR/dnstt-server.bak" "$INSTALL_DIR/dnstt-server"
            systemctl restart "$SERVICE_NAME"
        fi
    else
        print_error "Update failed"
        if [ -f "$INSTALL_DIR/dnstt-server.bak" ]; then
            mv "$INSTALL_DIR/dnstt-server.bak" "$INSTALL_DIR/dnstt-server"
        fi
    fi
}

# ==================== Main Installation ====================

do_install() {
    detect_os
    detect_arch
    load_config || true

    print_header

    # Get NS subdomain
    print_question "Enter nameserver subdomain (e.g., t.example.com)"
    if [ -n "$NS_SUBDOMAIN" ]; then
        echo -ne " [${YELLOW}$NS_SUBDOMAIN${NC}]: "
    else
        echo -ne ": "
    fi
    read -r input
    NS_SUBDOMAIN="${input:-$NS_SUBDOMAIN}"

    if [ -z "$NS_SUBDOMAIN" ]; then
        print_error "Subdomain is required"
        exit 1
    fi

    # Get MTU
    print_question "Enter MTU value [${YELLOW}${MTU_VALUE:-1232}${NC}]: "
    read -r input
    MTU_VALUE="${input:-${MTU_VALUE:-1232}}"

    # Get tunnel mode
    echo ""
    echo -e "${CYAN}Tunnel mode:${NC}"
    echo "  1) SOCKS proxy (Dante on 127.0.0.1:1080)"
    echo "  2) SSH"
    print_question "Select mode [${YELLOW}1${NC}]: "
    read -r mode_choice
    case "$mode_choice" in
        2) TUNNEL_MODE="ssh" ;;
        *) TUNNEL_MODE="socks" ;;
    esac

    echo ""
    echo -e "${CYAN}Configuration Summary:${NC}"
    echo -e "  Subdomain: ${YELLOW}$NS_SUBDOMAIN${NC}"
    echo -e "  MTU:       ${YELLOW}$MTU_VALUE${NC}"
    echo -e "  Mode:      ${YELLOW}$TUNNEL_MODE${NC}"
    echo ""
    print_question "Continue? (Y/n): "
    read -r confirm
    if [ "$confirm" = "n" ] || [ "$confirm" = "N" ]; then
        exit 0
    fi

    echo ""
    save_config
    install_dependencies
    create_user
    mkdir -p "$CONFIG_DIR"
    chown "$DNSTT_USER:$DNSTT_USER" "$CONFIG_DIR"
    chmod 750 "$CONFIG_DIR"
    install_binary
    generate_keys
    configure_iptables

    if [ "$TUNNEL_MODE" = "socks" ]; then
        install_dante
    fi

    create_service
    start_service
    install_script

    print_success_box
}

# ==================== Menu Actions ====================

do_status() {
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        print_status "Service is ${GREEN}running${NC}"
    else
        print_warning "Service is ${RED}stopped${NC}"
    fi
    echo ""
    systemctl status "$SERVICE_NAME" --no-pager -l
}

do_logs() {
    print_status "Showing logs (Ctrl+C to exit)..."
    journalctl -u "$SERVICE_NAME" -f
}

do_info() {
    if ! load_config; then
        print_warning "No configuration found. Run install first."
        return 1
    fi

    echo ""
    echo -e "${CYAN}Configuration:${NC}"
    echo -e "  Subdomain:    ${YELLOW}$NS_SUBDOMAIN${NC}"
    echo -e "  MTU:          ${YELLOW}$MTU_VALUE${NC}"
    echo -e "  Tunnel mode:  ${YELLOW}$TUNNEL_MODE${NC}"
    echo -e "  Listen port:  ${YELLOW}$DNSTT_PORT${NC}"
    echo ""

    if [ -f "$PUBLIC_KEY_FILE" ]; then
        echo -e "${CYAN}Public Key:${NC}"
        echo -e "${YELLOW}$(cat "$PUBLIC_KEY_FILE")${NC}"
    fi

    echo ""
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        echo -e "Service status: ${GREEN}Running${NC}"
    else
        echo -e "Service status: ${RED}Stopped${NC}"
    fi
    echo ""
}

# ==================== Menu ====================

show_menu() {
    clear
    print_header

    if [ "$UPDATE_AVAILABLE" = true ]; then
        echo -e "${YELLOW}[UPDATE AVAILABLE]${NC} New version of this script available!"
        echo ""
    fi

    echo "  1) Install / Reconfigure"
    echo "  2) Update binary"
    echo "  3) Update script"
    echo "  4) Service status"
    echo "  5) View logs"
    echo "  6) Show config info"
    echo "  0) Exit"
    echo ""
    print_question "Select option: "
}

handle_menu() {
    while true; do
        show_menu
        read -r choice

        case $choice in
            1) do_install ;;
            2) update_binary ;;
            3) update_script ;;
            4) do_status ;;
            5) do_logs ;;
            6) do_info ;;
            0)
                print_status "Goodbye!"
                exit 0
                ;;
            *)
                print_error "Invalid choice"
                ;;
        esac

        if [ "$choice" != "5" ]; then
            echo ""
            print_question "Press Enter to continue..."
            read -r
        fi
    done
}

# ==================== Main ====================

main() {
    check_for_updates

    if [ $# -eq 0 ]; then
        handle_menu
    else
        case "$1" in
            install)  do_install ;;
            update)   update_binary ;;
            status)   do_status ;;
            logs)     do_logs ;;
            info)     do_info ;;
            *)        handle_menu ;;
        esac
    fi
}

main "$@"
