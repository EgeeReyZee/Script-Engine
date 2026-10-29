"""
bridge.py — ScriptEngine Python bridge
"""

_api = None
_image_cache = {}

def set_api(api):
    global _api
    _api = api

# ─────────────────────────── Classes ───────────────────────────

# Чистый Python-класс, который заменит Kotlin Data Class
class Pos:
    def __init__(self, x, y, confidence):
        self.x = int(x)
        self.y = int(y)
        self.confidence = float(confidence)

    def __repr__(self):
        return f"Pos(x={self.x}, y={self.y}, conf={self.confidence:.2f})"

# ─────────────────────────── Screen ───────────────────────────

def get_color(x, y):
    try:
        return _api.getColor(int(x), int(y))
    except Exception as e:
        log(f"[bridge] get_color error: {e}")
        return -1

def get_colors(points):
    try:
        pts = [[int(p[0]), int(p[1])] for p in points]
        result = _api.getColors(pts)
        return list(result)
    except Exception as e:
        log(f"[bridge] get_colors error: {e}")
        return [-1] * len(points)

# ─────────────────────────── Images ───────────────────────────

def load_image(name):
    try:
        if name in _image_cache:
            img = _image_cache[name]
            if img is not None:
                return img
        img = _api.loadImage(str(name))
        if img is not None:
            _image_cache[name] = img
        return img
    except Exception as e:
        log(f"[bridge] load_image error: {e}")
        return None

def find_img(img, x1, y1, x2, y2, threshold=0.85):
    try:
        res = _api.findImg(img, int(x1), int(y1), int(x2), int(y2), float(threshold))
        if res is not None:
            # Безопасная распаковка Kotlin Point -> Python Pos
            try:
                return Pos(res.getX(), res.getY(), res.getConfidence())
            except:
                return Pos(res.x, res.y, res.confidence)
        return None
    except Exception as e:
        log(f"[bridge] find_img error: {e}")
        return None

def has_img(img, x1=0, y1=0, x2=None, y2=None, threshold=0.85):
    try:
        if x2 is None: x2 = 2147483647
        if y2 is None: y2 = 2147483647
        return bool(_api.hasImg(img, int(x1), int(y1), int(x2), int(y2), float(threshold)))
    except Exception as e:
        log(f"[bridge] has_img error: {e}")
        return False

def find_img_all(img, x1, y1, x2, y2, threshold=0.85):
    try:
        raw_list = _api.findImgAll(img, int(x1), int(y1), int(x2), int(y2), float(threshold))
        if raw_list is None:
            return []

        result = []
        for res in raw_list:
            # Распаковываем каждый элемент массива
            try:
                result.append(Pos(res.getX(), res.getY(), res.getConfidence()))
            except:
                result.append(Pos(res.x, res.y, res.confidence))
        return result
    except Exception as e:
        log(f"[bridge] find_img_all error: {e}")
        return []

# ─────────────────────────── Clicks ───────────────────────────

def click(x, y):
    try:
        _api.click(int(x), int(y))
    except Exception as e:
        log(f"[bridge] click error: {e}")

def click_rand(x, y, radius):
    try:
        _api.clickRand(int(x), int(y), int(radius))
    except Exception as e:
        log(f"[bridge] click_rand error: {e}")

def swipe(x1, y1, x2, y2, duration=300):
    try:
        _api.swipe(int(x1), int(y1), int(x2), int(y2), int(duration))
    except Exception as e:
        log(f"[bridge] swipe error: {e}")

def long_press(x, y, duration=1000):
    try:
        _api.longPress(int(x), int(y), int(duration))
    except Exception as e:
        log(f"[bridge] long_press error: {e}")

def press_back():
    try: _api.pressBack()
    except Exception as e: log(f"[bridge] press_back error: {e}")

def press_home():
    try: _api.pressHome()
    except Exception as e: log(f"[bridge] press_home error: {e}")

def show_recents():
    try: _api.showRecents()
    except Exception as e: log(f"[bridge] show_recents error: {e}")

# ─────────────────────────── Control ───────────────────────────

def sleep(ms):
    """Sleep with stop checking"""
    import time
    elapsed = 0
    step = min(100, ms)  # Проверяем флаг каждые 100ms
    while elapsed < ms:
        if should_stop():
            raise InterruptedError("Script stopped by user")
        time.sleep(step / 1000.0)
        elapsed += step
        step = min(step, ms - elapsed)

def log(message):
    try:
        _api.log(str(message))
    except Exception:
        pass

def get_screen_size():
    try:
        result = _api.getScreenSize()
        if isinstance(result, str):
            parts = result.split(",")
            return (int(parts[0]), int(parts[1]))
        return (int(result[0]), int(result[1]))
    except Exception as e:
        log(f"[bridge] get_screen_size error: {e}")
        return (0, 0)

def should_stop():
    """Check if script should stop"""
    import sys
    try:
        return sys.modules['__main__'].__dict__.get('__scriptengine_stopped', False)
    except:
        return False

# ─────────────────────────── ADB ───────────────────────────

def shell(command):
    try:
        return str(_api.shell(str(command)))
    except Exception as e:
        log(f"[bridge] shell error: {e}")
        return ""

def start_app(package_name, activity=None):
    try:
        _api.startApp(str(package_name), str(activity) if activity else None)
    except Exception as e:
        log(f"[bridge] start_app error: {e}")

def stop_app(package_name):
    try:
        _api.stopApp(str(package_name))
    except Exception as e:
        log(f"[bridge] stop_app error: {e}")

def send_key(keycode):
    try:
        _api.sendKey(int(keycode))
    except Exception as e:
        log(f"[bridge] send_key error: {e}")

def get_installed_apps():
    try:
        return list(_api.getInstalledApps())
    except Exception as e:
        log(f"[bridge] get_installed_apps error: {e}")
        return []

def set_clipboard(text):
    try:
        _api.setClipboardText(str(text))
    except Exception as e:
        log(f"[bridge] set_clipboard error: {e}")

def paste_text(text):
    """Копирует текст в буфер и нажимает кнопку Вставить"""
    set_clipboard(text)
    sleep(200) # Даем системе миллисекунды на обновление буфера

    # 279 — это стандартный Android KEYCODE_PASTE
    send_key(279)

def input_text(text):
    try:
        _api.inputAdbText(str(text))
    except Exception as e:
        log(f"[bridge] input_text error: {e}")

def complex_swipe(points, duration_per_segment=300):
    """
    Безотрывный свайп по массиву точек.
    Пример: complex_swipe([(500, 500), (500, 800), (800, 800)])
    """
    try:
        # Конвертируем список кортежей в формат, который поймет Kotlin (например, строку "x1,y1;x2,y2")
        pts_str = ";".join([f"{int(p[0])},{int(p[1])}" for p in points])
        _api.complexSwipe(pts_str, int(duration_per_segment))
    except Exception as e:
        log(f"[bridge] complex_swipe error: {e}")