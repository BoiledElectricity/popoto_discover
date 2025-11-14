#!/usr/bin/env python3
"""
Popoto Discovery Tool - GUI Application

A PyQt5-based graphical interface for discovering and managing popoto devices.
"""

import sys
import os
import json
import logging
import webbrowser
import time
from datetime import datetime
from typing import Optional, List, Dict, Any

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QTableWidget, QTableWidgetItem, QLabel, QLineEdit,
    QDialog, QDialogButtonBox, QFormLayout, QTextEdit, QMessageBox,
    QStatusBar, QGroupBox, QFileDialog, QCheckBox, QSpinBox, QDoubleSpinBox,
    QComboBox, QHeaderView, QTabWidget, QSplitter
)
from PyQt5.QtCore import Qt, QThread, pyqtSignal, QTimer
from PyQt5.QtGui import QFont, QColor, QPixmap
from PyQt5.QtSvg import QSvgWidget

# Add parent directory to path to import common module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from common import protocol

# Import host functions
import popoto_discover as pd

# Configure logging
logger = logging.getLogger(__name__)


class DiscoveryThread(QThread):
    """Background thread for device discovery."""

    finished = pyqtSignal(list)
    error = pyqtSignal(str)

    def __init__(self, timeout: float, secret: Optional[str]):
        super().__init__()
        self.timeout = timeout
        self.secret = secret

    def run(self):
        """Execute discovery in background."""
        try:
            devices = pd.discover(timeout=self.timeout, secret=self.secret)
            self.finished.emit(devices)
        except Exception as e:
            self.error.emit(str(e))


class SetIPDialog(QDialog):
    """Dialog for setting IP address on a device."""

    def __init__(self, mac: str, current_ip: str = "", current_netmask: str = "255.255.255.0",
                 current_gateway: str = "", parent=None):
        super().__init__(parent)
        self.mac = mac
        self.current_ip = current_ip
        self.current_netmask = current_netmask
        self.current_gateway = current_gateway
        self.setWindowTitle(f"Set IP Address - {mac}")
        self.setup_ui()

    def setup_ui(self):
        layout = QFormLayout()

        # DHCP checkbox
        self.dhcp_checkbox = QCheckBox("Use DHCP")
        self.dhcp_checkbox.stateChanged.connect(self.toggle_dhcp)
        layout.addRow("", self.dhcp_checkbox)

        self.ip_edit = QLineEdit()
        self.ip_edit.setText(self.current_ip)
        self.ip_edit.setPlaceholderText("192.168.1.100")
        layout.addRow("New IP Address:", self.ip_edit)

        self.netmask_edit = QLineEdit()
        self.netmask_edit.setText(self.current_netmask)
        layout.addRow("Netmask:", self.netmask_edit)

        self.gateway_edit = QLineEdit()
        self.gateway_edit.setText(self.current_gateway)
        self.gateway_edit.setPlaceholderText("192.168.1.1")
        layout.addRow("Gateway:", self.gateway_edit)

        self.timeout_spin = QDoubleSpinBox()
        self.timeout_spin.setValue(5.0)
        self.timeout_spin.setRange(1.0, 30.0)
        self.timeout_spin.setSuffix(" sec")
        layout.addRow("Timeout:", self.timeout_spin)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

        self.setLayout(layout)

    def toggle_dhcp(self, state):
        """Enable/disable IP fields based on DHCP checkbox."""
        is_dhcp = state == Qt.Checked
        self.ip_edit.setEnabled(not is_dhcp)
        self.netmask_edit.setEnabled(not is_dhcp)
        self.gateway_edit.setEnabled(not is_dhcp)

    def get_values(self):
        return {
            'use_dhcp': self.dhcp_checkbox.isChecked(),
            'ip': self.ip_edit.text(),
            'netmask': self.netmask_edit.text(),
            'gateway': self.gateway_edit.text(),
            'timeout': self.timeout_spin.value()
        }


class SetRTCDialog(QDialog):
    """Dialog for setting real-time clock on a device."""

    def __init__(self, mac: str, parent=None):
        super().__init__(parent)
        self.mac = mac
        self.setWindowTitle(f"Set Real-Time Clock - {mac}")
        self.setup_ui()

    def setup_ui(self):
        layout = QFormLayout()

        # Current time button
        current_time_btn = QPushButton("Use Current Time")
        current_time_btn.clicked.connect(self.use_current_time)
        layout.addRow(current_time_btn)

        self.rtc_edit = QLineEdit()
        self.rtc_edit.setPlaceholderText("YYYY.MM.DD-HH:MM:SS")
        layout.addRow("RTC String:", self.rtc_edit)

        # Format help
        help_label = QLabel("Format: YYYY.MM.DD-HH:MM:SS\nExample: 2025.11.09-14:30:00")
        help_label.setStyleSheet("color: gray; font-size: 10px;")
        layout.addRow(help_label)

        self.timeout_spin = QDoubleSpinBox()
        self.timeout_spin.setValue(5.0)
        self.timeout_spin.setRange(1.0, 30.0)
        self.timeout_spin.setSuffix(" sec")
        layout.addRow("Timeout:", self.timeout_spin)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

        self.setLayout(layout)

        # Set current time by default
        self.use_current_time()

    def use_current_time(self):
        """Fill in current system time."""
        now = datetime.now()
        rtc_str = now.strftime("%Y.%m.%d-%H:%M:%S")
        self.rtc_edit.setText(rtc_str)

    def get_values(self):
        return {
            'rtc': self.rtc_edit.text(),
            'timeout': self.timeout_spin.value()
        }


class SetParamDialog(QDialog):
    """Dialog for setting a popoto parameter on a device."""

    def __init__(self, mac: str, parent=None):
        super().__init__(parent)
        self.mac = mac
        self.setWindowTitle(f"Set Parameter - {mac}")
        self.setup_ui()

    def setup_ui(self):
        layout = QFormLayout()

        # Common parameters dropdown with custom option
        self.param_combo = QComboBox()
        common_params = [
            "TxPowerWatts",
            "RecordMode",
            "PlayMode",
            "PayloadMode",
            "ChannelInputMask",
            "Custom..."
        ]
        self.param_combo.addItems(common_params)
        self.param_combo.currentTextChanged.connect(self.on_param_changed)
        layout.addRow("Parameter:", self.param_combo)

        self.param_custom_edit = QLineEdit()
        self.param_custom_edit.setPlaceholderText("Custom parameter name")
        self.param_custom_edit.setVisible(False)
        layout.addRow("Custom Name:", self.param_custom_edit)

        self.value_edit = QLineEdit()
        self.value_edit.setPlaceholderText("Value (int or float)")
        layout.addRow("Value:", self.value_edit)

        # Help text
        help_label = QLabel("Examples:\nTxPowerWatts: 2.5\nRecordMode: 0 or 1\nPayloadMode: 0-5")
        help_label.setStyleSheet("color: gray; font-size: 10px;")
        layout.addRow(help_label)

        self.timeout_spin = QDoubleSpinBox()
        self.timeout_spin.setValue(5.0)
        self.timeout_spin.setRange(1.0, 30.0)
        self.timeout_spin.setSuffix(" sec")
        layout.addRow("Timeout:", self.timeout_spin)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

        self.setLayout(layout)

    def on_param_changed(self, text):
        """Show/hide custom parameter field."""
        is_custom = text == "Custom..."
        self.param_custom_edit.setVisible(is_custom)

    def get_values(self):
        param_name = self.param_combo.currentText()
        if param_name == "Custom...":
            param_name = self.param_custom_edit.text()

        return {
            'param_name': param_name,
            'param_value': self.value_edit.text(),
            'timeout': self.timeout_spin.value()
        }


class MainWindow(QMainWindow):
    """Main application window."""

    def __init__(self):
        super().__init__()
        self.devices = []
        self.secret = None
        self.device_times = {}  # Store device RTC values with timestamps
        self.setup_logging()
        self.setup_ui()
        self.load_settings()

    def setup_logging(self):
        """Configure logging to text widget."""
        self.log_handler = logging.Handler()
        self.log_handler.setLevel(logging.INFO)
        formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
        self.log_handler.setFormatter(formatter)

    def setup_ui(self):
        """Set up the user interface."""
        self.setWindowTitle("Popoto Discovery Tool")
        self.setGeometry(100, 100, 1200, 700)

        # Apply Popoto color scheme and styling
        self.setStyleSheet("""
            /* Popoto Color Scheme */
            QMainWindow, QDialog, QWidget {
                background-color: #f2f4f7;
                color: #434343;
                font-family: Arial, sans-serif;
                font-size: 10pt;
            }

            /* Group boxes */
            QGroupBox {
                background-color: white;
                border: 1px solid #a1a1a1;
                border-radius: 6px;
                margin-top: 10px;
                padding: 15px;
                font-weight: 500;
                color: #434343;
            }

            QGroupBox::title {
                subcontrol-origin: margin;
                left: 10px;
                padding: 0 5px;
                color: #434343;
            }

            /* Buttons */
            QPushButton {
                background-color: #2777c3;
                color: white;
                border: none;
                border-radius: 5px;
                padding: 6px 14px;
                font-weight: 700;
                min-height: 20px;
            }

            QPushButton:hover {
                background-color: #21608a;
            }

            QPushButton:pressed {
                background-color: #234c6f;
            }

            QPushButton:disabled {
                background-color: #a1a1a1;
            }

            /* Text inputs */
            QLineEdit, QTextEdit, QSpinBox, QDoubleSpinBox, QComboBox {
                background-color: white;
                color: #434343;
                border: 1px solid #727272;
                border-radius: 6px;
                padding: 4px 10px;
                min-height: 16px;
            }

            QLineEdit:focus, QTextEdit:focus, QSpinBox:focus, QDoubleSpinBox:focus {
                border: 2px solid #2777c3;
            }

            /* Table */
            QTableWidget {
                background-color: white;
                alternate-background-color: #f2f4f7;
                gridline-color: #a1a1a1;
                border: 1px solid #a1a1a1;
                border-radius: 6px;
            }

            QTableWidget::item {
                padding: 5px;
                color: #434343;
            }

            QTableWidget::item:selected {
                background-color: #2777c3;
                color: white;
            }

            QHeaderView::section {
                background-color: #234c6f;
                color: white;
                padding: 6px;
                border: none;
                font-weight: 700;
            }

            /* Checkboxes */
            QCheckBox {
                color: #434343;
                spacing: 8px;
            }

            QCheckBox::indicator {
                width: 18px;
                height: 18px;
                border: 1px solid #727272;
                border-radius: 3px;
                background-color: white;
            }

            QCheckBox::indicator:checked {
                background-color: #2777c3;
                border-color: #2777c3;
            }

            /* Labels */
            QLabel {
                color: #434343;
            }

            /* Status bar */
            QStatusBar {
                background-color: #234c6f;
                color: white;
                border-top: 1px solid #727272;
            }

            /* Tab widget */
            QTabWidget::pane {
                border: 1px solid #a1a1a1;
                background-color: white;
                border-radius: 6px;
            }

            QTabBar::tab {
                background-color: #f2f4f7;
                color: #434343;
                border: 1px solid #a1a1a1;
                padding: 8px 16px;
                margin-right: 2px;
            }

            QTabBar::tab:selected {
                background-color: #2777c3;
                color: white;
            }

            QTabBar::tab:hover {
                background-color: #21608a;
                color: white;
            }
        """)

        # Central widget with splitter
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)

        # Top section: Logo and branding
        header_layout = QHBoxLayout()

        # Popoto logo
        logo_path = os.path.join(os.path.dirname(__file__), 'popoto-logo.svg')
        if os.path.exists(logo_path):
            logo_widget = QSvgWidget(logo_path)
            # Set only height, let width scale to maintain aspect ratio
            logo_widget.setFixedHeight(50)
            logo_widget.setMaximumWidth(300)
            header_layout.addWidget(logo_widget)
        else:
            # Fallback to text if logo not found
            logo_label = QLabel("Popoto Discovery Tool")
            logo_label.setFont(QFont("Arial", 16, QFont.Bold))
            logo_label.setStyleSheet("color: #2777c3;")
            header_layout.addWidget(logo_label)

        header_layout.addStretch()

        # Computer current time display
        self.computer_time_label = QLabel()
        self.computer_time_label.setFont(QFont("Arial", 14, QFont.Bold))
        self.computer_time_label.setStyleSheet("color: #2777c3; padding: 10px;")
        header_layout.addWidget(self.computer_time_label)

        # Timer to update computer time every second
        self.computer_time_timer = QTimer()
        self.computer_time_timer.timeout.connect(self.update_computer_time)
        self.computer_time_timer.start(1000)  # Update every second
        self.update_computer_time()  # Initial update

        # Hidden secret file path (keep for functionality)
        self.secret_edit = QLineEdit()
        self.secret_edit.setText(protocol.DEFAULT_SECRET_FILE)
        self.secret_edit.setVisible(False)

        # Hidden auth checkbox (keep for functionality)
        self.no_auth_check = QCheckBox()
        self.no_auth_check.setVisible(False)

        main_layout.addLayout(header_layout)

        # Middle section: Device table and controls
        splitter = QSplitter(Qt.Vertical)

        # Device table section
        table_widget = QWidget()
        table_layout = QVBoxLayout(table_widget)

        table_header_layout = QHBoxLayout()
        table_label = QLabel("Discovered Devices:")
        table_label.setFont(QFont("Arial", 12, QFont.Bold))
        table_header_layout.addWidget(table_label)
        table_header_layout.addStretch()

        # Discovery controls
        # Fixed timeout of 5 seconds
        self.discovery_timeout = 5.0

        self.discover_btn = QPushButton("Discover Devices")
        self.discover_btn.clicked.connect(self.discover_devices)
        self.discover_btn.setMinimumHeight(35)
        table_header_layout.addWidget(self.discover_btn)

        table_layout.addLayout(table_header_layout)

        # Device table
        self.device_table = QTableWidget()
        self.device_table.setColumnCount(10)
        self.device_table.setHorizontalHeaderLabels([
            "Name", "Model", "Serial", "IP", "MAC", "Firmware",
            "Battery (V)", "Sample Rate (Hz)", "Device Time", "Storage Used/Total (GB)"
        ])
        self.device_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        self.device_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.device_table.setSelectionMode(QTableWidget.SingleSelection)
        self.device_table.setAlternatingRowColors(True)
        table_layout.addWidget(self.device_table)

        # Action buttons
        action_layout = QHBoxLayout()

        self.set_ip_btn = QPushButton("Set IP Address")
        self.set_ip_btn.clicked.connect(self.set_ip_address)
        self.set_ip_btn.setEnabled(False)

        self.set_rtc_btn = QPushButton("Set RTC")
        self.set_rtc_btn.clicked.connect(self.set_rtc)
        self.set_rtc_btn.setEnabled(False)

        self.get_rtc_btn = QPushButton("Get RTC")
        self.get_rtc_btn.clicked.connect(self.get_rtc)
        self.get_rtc_btn.setEnabled(False)

        self.set_param_btn = QPushButton("Set Parameter")
        self.set_param_btn.clicked.connect(self.set_parameter)
        self.set_param_btn.setEnabled(False)

        action_layout.addWidget(self.set_ip_btn)
        action_layout.addWidget(self.set_rtc_btn)
        action_layout.addWidget(self.get_rtc_btn)
        action_layout.addWidget(self.set_param_btn)
        action_layout.addStretch()

        table_layout.addLayout(action_layout)

        splitter.addWidget(table_widget)

        # Log section
        log_widget = QWidget()
        log_layout = QVBoxLayout(log_widget)

        log_label = QLabel("Activity Log:")
        log_label.setFont(QFont("Arial", 10, QFont.Bold))
        log_layout.addWidget(log_label)

        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setMaximumHeight(200)
        self.log_text.setFont(QFont("Courier", 9))
        log_layout.addWidget(self.log_text)

        clear_log_btn = QPushButton("Clear Log")
        clear_log_btn.clicked.connect(self.log_text.clear)
        log_layout.addWidget(clear_log_btn)

        splitter.addWidget(log_widget)

        # Set splitter sizes
        splitter.setSizes([500, 200])

        main_layout.addWidget(splitter)

        # Status bar
        self.statusBar = QStatusBar()
        self.setStatusBar(self.statusBar)
        self.statusBar.showMessage("Ready")

        # Connect table selection
        self.device_table.itemSelectionChanged.connect(self.on_device_selected)
        self.device_table.itemDoubleClicked.connect(self.on_device_double_clicked)

        # Timer to query device times every 30 seconds
        self.device_time_query_timer = QTimer()
        self.device_time_query_timer.timeout.connect(self.query_all_device_times)
        self.device_time_query_timer.start(30000)  # Query every 30 seconds

        # Timer to update display every second (with interpolation)
        self.device_time_display_timer = QTimer()
        self.device_time_display_timer.timeout.connect(self.update_device_time_display)
        self.device_time_display_timer.start(1000)  # Update display every second

        # Auto-discover on startup
        QTimer.singleShot(500, self.discover_devices)

        # Start initial device time poll after discovery completes
        QTimer.singleShot(6000, self.query_all_device_times)

    def log(self, message: str, level: str = "INFO"):
        """Add message to log."""
        timestamp = datetime.now().strftime("%H:%M:%S")
        color = {
            "INFO": "black",
            "ERROR": "red",
            "SUCCESS": "green",
            "WARNING": "orange"
        }.get(level, "black")

        self.log_text.append(f'<span style="color: {color};">[{timestamp}] {level}: {message}</span>')
        self.log_text.verticalScrollBar().setValue(
            self.log_text.verticalScrollBar().maximum()
        )

    def load_settings(self):
        """Load authentication settings."""
        if protocol.AUTH_ENABLED:
            self.load_secret()

    def browse_secret_file(self):
        """Browse for secret file."""
        file_path, _ = QFileDialog.getOpenFileName(
            self,
            "Select Secret File",
            ".",
            "All Files (*)"
        )
        if file_path:
            self.secret_edit.setText(file_path)

    def load_secret(self):
        """Load the shared secret."""
        if self.no_auth_check.isChecked():
            self.secret = None
            self.log("Authentication disabled", "WARNING")
            self.statusBar.showMessage("Authentication: DISABLED", 3000)
            return

        try:
            secret_file = self.secret_edit.text()
            self.secret = protocol.load_shared_secret(secret_file)
            self.log("Authentication enabled", "SUCCESS")
            self.statusBar.showMessage("Authentication: ENABLED", 3000)
        except FileNotFoundError as e:
            self.log(f"Secret file not found: {e}", "ERROR")
            QMessageBox.warning(self, "Error", str(e))
            self.secret = None
        except ValueError as e:
            self.log(f"Invalid secret file: {e}", "ERROR")
            QMessageBox.warning(self, "Error", str(e))
            self.secret = None

    def discover_devices(self):
        """Start device discovery."""
        self.log("Starting device discovery...")
        self.discover_btn.setEnabled(False)
        self.statusBar.showMessage("Discovering devices...")

        # Start discovery in background thread
        self.discovery_thread = DiscoveryThread(
            timeout=self.discovery_timeout,
            secret=self.secret
        )
        self.discovery_thread.finished.connect(self.on_discovery_finished)
        self.discovery_thread.error.connect(self.on_discovery_error)
        self.discovery_thread.start()

    def on_discovery_finished(self, devices: List[Dict[str, Any]]):
        """Handle discovery completion."""
        # Deduplicate devices by serial number (use MAC as secondary key)
        unique_devices = {}
        for device in devices:
            serial = device.get('serial', '')
            mac = device.get('mac', '')
            # Use serial as primary key, MAC as fallback
            key = serial if serial else mac

            # Keep first occurrence or update if we find better data
            if key not in unique_devices:
                unique_devices[key] = device

        self.devices = list(unique_devices.values())
        self.discover_btn.setEnabled(True)

        original_count = len(devices)
        unique_count = len(self.devices)

        if not self.devices:
            self.log("No devices discovered", "WARNING")
            self.statusBar.showMessage("No devices found", 3000)
        else:
            if original_count > unique_count:
                self.log(f"Discovered {original_count} responses, {unique_count} unique device(s)", "SUCCESS")
            else:
                self.log(f"Discovered {unique_count} device(s)", "SUCCESS")
            self.statusBar.showMessage(f"Found {unique_count} device(s)", 3000)

        self.update_device_table()

    def on_discovery_error(self, error_msg: str):
        """Handle discovery error."""
        self.discover_btn.setEnabled(True)
        self.log(f"Discovery error: {error_msg}", "ERROR")
        self.statusBar.showMessage("Discovery failed", 3000)
        QMessageBox.critical(self, "Discovery Error", error_msg)

    def update_device_table(self):
        """Update the device table with discovered devices."""
        # Sort devices by IP address
        def ip_sort_key(device):
            ip = device.get('ip', '0.0.0.0')
            try:
                # Convert IP to tuple of integers for proper sorting
                return tuple(int(part) for part in ip.split('.'))
            except:
                return (0, 0, 0, 0)

        self.devices.sort(key=ip_sort_key)

        self.device_table.setRowCount(len(self.devices))

        for row, device in enumerate(self.devices):
            self.device_table.setItem(row, 0, QTableWidgetItem(device.get('name', '')))
            self.device_table.setItem(row, 1, QTableWidgetItem(device.get('model', '')))
            self.device_table.setItem(row, 2, QTableWidgetItem(device.get('serial', '')))
            self.device_table.setItem(row, 3, QTableWidgetItem(device.get('ip', '')))
            self.device_table.setItem(row, 4, QTableWidgetItem(device.get('mac', '')))
            self.device_table.setItem(row, 5, QTableWidgetItem(device.get('fw', '')))
            self.device_table.setItem(row, 6, QTableWidgetItem(str(device.get('battery_v', ''))))
            self.device_table.setItem(row, 7, QTableWidgetItem(str(device.get('sample_rate_hz', ''))))
            self.device_table.setItem(row, 8, QTableWidgetItem('--:--:--'))  # Device time placeholder

            storage_free = device.get('storage_free_gb', 0)
            storage_total = device.get('storage_total_gb', 0)
            storage_used = storage_total - storage_free
            storage_str = f"{storage_used:.1f} / {storage_total:.1f}"
            self.device_table.setItem(row, 9, QTableWidgetItem(storage_str))

    def update_computer_time(self):
        """Update the computer's current time display."""
        now = datetime.now()
        time_str = now.strftime("%Y.%m.%d-%H:%M:%S")
        self.computer_time_label.setText(f"Computer Time: {time_str}")

    def query_all_device_times(self):
        """Query RTC from all discovered devices."""
        if not self.devices:
            return

        for device in self.devices:
            mac = device.get('mac', '')
            if not mac:
                continue

            try:
                # Query device RTC in background (non-blocking)
                resp = pd.get_rtc(mac, timeout=2.0, secret=self.secret)

                if resp and resp.get('status') == 'ok':
                    rtc_value = resp.get('rtc', '')
                    if rtc_value:
                        # Store the time and when we retrieved it
                        self.device_times[mac] = {
                            'time': rtc_value,
                            'retrieved_at': time.time()
                        }
            except Exception as e:
                logger.debug(f"Error querying RTC for {mac}: {e}")

    def update_device_time_display(self):
        """Update the device time display with interpolation."""
        current_time = time.time()

        for row in range(self.device_table.rowCount()):
            if row >= len(self.devices):
                continue

            device = self.devices[row]
            mac = device.get('mac', '')

            if mac in self.device_times:
                device_time_data = self.device_times[mac]
                rtc_str = device_time_data['time']
                retrieved_at = device_time_data['retrieved_at']

                try:
                    # Parse the RTC time (format: YYYY.MM.DD-HH:MM:SS)
                    dt = datetime.strptime(rtc_str, "%Y.%m.%d-%H:%M:%S")

                    # Add elapsed seconds since we retrieved it
                    elapsed = int(current_time - retrieved_at)
                    from datetime import timedelta
                    interpolated_time = dt + timedelta(seconds=elapsed)

                    # Format for display (just time, not date)
                    time_display = interpolated_time.strftime("%H:%M:%S")

                    item = self.device_table.item(row, 8)
                    if item:
                        item.setText(time_display)
                except Exception as e:
                    logger.debug(f"Error interpolating time for {mac}: {e}")

    def on_device_selected(self):
        """Handle device selection change."""
        has_selection = len(self.device_table.selectedItems()) > 0
        self.set_ip_btn.setEnabled(has_selection)
        self.set_rtc_btn.setEnabled(has_selection)
        self.get_rtc_btn.setEnabled(has_selection)
        self.set_param_btn.setEnabled(has_selection)

    def on_device_double_clicked(self, item):
        """Handle device double-click to open webpage."""
        device = self.get_selected_device()
        if not device:
            return

        ip = device.get('ip', '')
        http_port = device.get('http_port', 80)

        if not ip:
            self.log("No IP address for device", "WARNING")
            return

        # Construct URL
        url = f"http://{ip}:{http_port}"

        self.log(f"Opening {url} in browser...")
        try:
            webbrowser.open(url)
        except Exception as e:
            self.log(f"Failed to open browser: {e}", "ERROR")
            QMessageBox.warning(self, "Browser Error", f"Could not open browser:\n{e}")

    def get_selected_device(self) -> Optional[Dict[str, Any]]:
        """Get the currently selected device."""
        selected_rows = self.device_table.selectionModel().selectedRows()
        if not selected_rows:
            return None

        row = selected_rows[0].row()
        return self.devices[row] if row < len(self.devices) else None

    def set_ip_address(self):
        """Open dialog to set IP address."""
        device = self.get_selected_device()
        if not device:
            return

        mac = device.get('mac', '')
        current_ip = device.get('ip', '')
        current_netmask = device.get('netmask', '255.255.255.0')
        current_gateway = device.get('gateway', '')

        # Fallback: Calculate default gateway from current IP if not provided
        if not current_gateway and current_ip:
            parts = current_ip.split('.')
            if len(parts) == 4:
                current_gateway = f"{parts[0]}.{parts[1]}.{parts[2]}.1"

        dialog = SetIPDialog(
            mac=mac,
            current_ip=current_ip,
            current_netmask=current_netmask,
            current_gateway=current_gateway,
            parent=self
        )

        if dialog.exec_() == QDialog.Accepted:
            values = dialog.get_values()
            if values['use_dhcp']:
                self.log(f"Configuring {mac} to use DHCP...")
                self.statusBar.showMessage("Configuring DHCP...")
            else:
                self.log(f"Setting IP on {mac} to {values['ip']}...")
                self.statusBar.showMessage("Setting IP address...")

            # Call set_ip function
            try:
                resp = pd.set_ip(
                    mac, values['ip'], values['netmask'], values['gateway'],
                    timeout=values['timeout'], secret=self.secret, use_dhcp=values['use_dhcp']
                )

                if resp and resp.get('status') == 'ok':
                    if values['use_dhcp']:
                        self.log("DHCP configured successfully", "SUCCESS")
                        QMessageBox.information(
                            self,
                            "Success",
                            f"DHCP configured successfully\nDevice replied from {resp.get('_source_ip')}"
                        )
                    else:
                        self.log(f"IP set successfully to {values['ip']}", "SUCCESS")
                        QMessageBox.information(
                            self,
                            "Success",
                            f"IP address set to {values['ip']}\nDevice replied from {resp.get('_source_ip')}"
                        )
                    # Refresh device list
                    QTimer.singleShot(1000, self.discover_devices)
                else:
                    error_msg = resp.get('error', 'Unknown error') if resp else 'No response (timeout)'
                    self.log(f"Failed to set IP: {error_msg}", "ERROR")
                    QMessageBox.warning(self, "Failed", f"Failed to set IP:\n{error_msg}")

                self.statusBar.showMessage("Ready", 3000)
            except Exception as e:
                self.log(f"Error setting IP: {e}", "ERROR")
                QMessageBox.critical(self, "Error", str(e))
                self.statusBar.showMessage("Ready", 3000)

    def set_rtc(self):
        """Open dialog to set RTC."""
        device = self.get_selected_device()
        if not device:
            return

        mac = device.get('mac', '')
        dialog = SetRTCDialog(mac, self)

        if dialog.exec_() == QDialog.Accepted:
            values = dialog.get_values()
            self.log(f"Setting RTC on {mac} to {values['rtc']}...")
            self.statusBar.showMessage("Setting RTC...")

            try:
                resp = pd.set_rtc(
                    mac, values['rtc'],
                    timeout=values['timeout'], secret=self.secret
                )

                if resp and resp.get('status') == 'ok':
                    self.log(f"RTC set successfully to {values['rtc']}", "SUCCESS")
                    QMessageBox.information(
                        self,
                        "Success",
                        f"RTC set to {values['rtc']}\nDevice replied from {resp.get('_source_ip')}"
                    )
                else:
                    error_msg = resp.get('error', 'Unknown error') if resp else 'No response (timeout)'
                    self.log(f"Failed to set RTC: {error_msg}", "ERROR")
                    QMessageBox.warning(self, "Failed", f"Failed to set RTC:\n{error_msg}")

                self.statusBar.showMessage("Ready", 3000)
            except Exception as e:
                self.log(f"Error setting RTC: {e}", "ERROR")
                QMessageBox.critical(self, "Error", str(e))
                self.statusBar.showMessage("Ready", 3000)

    def get_rtc(self):
        """Get RTC from selected device."""
        device = self.get_selected_device()
        if not device:
            return

        mac = device.get('mac', '')
        self.log(f"Getting RTC from {mac}...")
        self.statusBar.showMessage("Getting RTC...")

        try:
            resp = pd.get_rtc(mac, timeout=self.discovery_timeout, secret=self.secret)

            if resp and resp.get('status') == 'ok':
                rtc_value = resp.get('rtc', 'Unknown')
                self.log(f"RTC value: {rtc_value}", "SUCCESS")
                QMessageBox.information(
                    self,
                    "RTC Value",
                    f"Device: {device.get('name', mac)}\nRTC: {rtc_value}\nReply from: {resp.get('_source_ip')}"
                )
            else:
                error_msg = resp.get('error', 'Unknown error') if resp else 'No response (timeout)'
                self.log(f"Failed to get RTC: {error_msg}", "ERROR")
                QMessageBox.warning(self, "Failed", f"Failed to get RTC:\n{error_msg}")

            self.statusBar.showMessage("Ready", 3000)
        except Exception as e:
            self.log(f"Error getting RTC: {e}", "ERROR")
            QMessageBox.critical(self, "Error", str(e))
            self.statusBar.showMessage("Ready", 3000)

    def set_parameter(self):
        """Open dialog to set parameter."""
        device = self.get_selected_device()
        if not device:
            return

        mac = device.get('mac', '')
        dialog = SetParamDialog(mac, self)

        if dialog.exec_() == QDialog.Accepted:
            values = dialog.get_values()
            self.log(f"Setting {values['param_name']} on {mac} to {values['param_value']}...")
            self.statusBar.showMessage("Setting parameter...")

            try:
                resp = pd.set_param(
                    mac, values['param_name'], values['param_value'],
                    timeout=values['timeout'], secret=self.secret
                )

                if resp and resp.get('status') == 'ok':
                    self.log(f"Parameter {values['param_name']} set successfully", "SUCCESS")
                    QMessageBox.information(
                        self,
                        "Success",
                        f"Parameter {values['param_name']} set to {values['param_value']}\n"
                        f"Device replied from {resp.get('_source_ip')}"
                    )
                else:
                    error_msg = resp.get('error', 'Unknown error') if resp else 'No response (timeout)'
                    self.log(f"Failed to set parameter: {error_msg}", "ERROR")
                    QMessageBox.warning(self, "Failed", f"Failed to set parameter:\n{error_msg}")

                self.statusBar.showMessage("Ready", 3000)
            except Exception as e:
                self.log(f"Error setting parameter: {e}", "ERROR")
                QMessageBox.critical(self, "Error", str(e))
                self.statusBar.showMessage("Ready", 3000)


def main():
    """Main entry point."""
    app = QApplication(sys.argv)
    app.setStyle('Fusion')  # Modern look

    window = MainWindow()
    window.show()

    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
