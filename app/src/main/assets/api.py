"""
api.py — Public API for user scripts.
======================================
User scripts do: from api import *

This re-exports everything from bridge so that:
1. `from api import *` works as expected
2. The bridge internals stay separate from the user-facing namespace
"""

from bridge import (
    # Screen
    get_color,
    get_colors,
    # Images
    load_image,
    find_img,
    has_img,
    find_img_all,
    # Clicks
    click,
    click_rand,
    swipe,
    long_press,
    press_back,
    press_home,
    show_recents,
    complex_swipe,
    # Control
    sleep,
    log,
    get_screen_size,
    # ADB
    shell,
    start_app,
    stop_app,
    send_key,
    get_installed_apps,
    # TEXT
    set_clipboard,
    paste_text,
    input_text
)

__all__ = [
    'get_color', 'get_colors',
    'load_image', 'find_img', 'has_img', 'find_img_all',
    'click', 'click_rand', 'swipe', 'long_press', 'complex_swipe',
    'press_back', 'press_home', 'show_recents',
    'sleep', 'log', 'get_screen_size',
    'shell', 'start_app', 'stop_app', 'send_key', 'get_installed_apps',
    'set_clipboard', 'paste_text', 'input_text'
]
