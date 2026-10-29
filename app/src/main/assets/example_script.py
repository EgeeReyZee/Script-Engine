"""
example_script.py — Example automation script.
Copy to /sdcard/scripts/ and run from ScriptEngine.
"""
from api import *
import threading

RED = 3005659
btn = load_image("button.png")
counter = 0

def watch_thread():
    while True:
        if get_color(800, 720) == RED:
            click(800, 720)
            sleep(500)
        sleep(100)

def main_thread():
    global counter
    while True:
        pos = find_img(btn, 0, 0, 1600, 900)
        if pos:
            click(pos.x, pos.y)
            counter += 1
            log(f"Clicked {counter} times")
        sleep(200)

t = threading.Thread(target=watch_thread, daemon=True)
t.start()
main_thread()
