import automator
import time

PACKAGE_NAME = "" 

def bounds_to_center(bounds):
    if not bounds: return 0, 0
    try:
        parts = bounds.split(",")
        l, t, r, b = map(int, parts)
        return (l + r) // 2, (t + b) // 2
    except:
        return 0, 0

def perform_swipe(direction):
    w, h = automator.get_screen_size()
    # Center points
    cx, cy = w // 2, h // 2
    
    print(f"[+] Swiping {direction} (Screen: {w}x{h})...")
    
    if direction == "up":
        automator.swipe(cx, h * 0.8, cx, h * 0.2, 500)
    elif direction == "dw":
        automator.swipe(cx, h * 0.2, cx, h * 0.8, 500)
    elif direction == "lt":
        automator.swipe(w * 0.8, cy, w * 0.2, cy, 500)
    elif direction == "rt":
        automator.swipe(w * 0.2, cy, w * 0.8, cy, 500)
    
    time.sleep(1.0) # Wait for animation

def main():
    print(f"[*] Advanced UI Interaction ({PACKAGE_NAME})")
    print("[*] Dashboard Terminal Interactive Mode\n")

    while True:
        try:
            automator.clear_logs()
            
            elements = automator.get_interactable_elements()
            if PACKAGE_NAME:
                elements = [el for el in elements if PACKAGE_NAME in el.get("package", "")]

            print(f"[*] Interactable elements found ({len(elements)}):")
            print("--- Shortcuts: [b]ack, [h]ome, [rec]ent, [up]/[dw]/[lt]/[rt] ---")
            print("")

            for i, el in enumerate(elements, start=1):
                text = el.get('text') or el.get('desc') or "(no text)"
                text_disp = text.strip()[:30]
                path = el.get('path', 'n/a')
                res_id = el.get('id', 'n/a')
                cls = el.get('class', 'n/a').split('.')[-1]
                bnds = el.get('bounds', '0,0,0,0')
                print(f"{i:02d}. {text_disp:<25} | {res_id:<25} | {path:<15} | [{bnds}] | {cls}")
                
            cmd = input("\n[?] Enter # or shortcut: ").strip().lower()
            if not cmd:
                continue

            # Navigation Shortcuts
            if cmd == 'b':
                print("[+] Pressing Back...")
                automator.press_back()
                time.sleep(1.0)
            elif cmd == 'h':
                print("[+] Pressing Home...")
                automator.press_home()
                time.sleep(1.5)
            elif cmd == 'rec':
                print("[+] Pressing Recent...")
                automator.press_recent()
                time.sleep(1.0)
            elif cmd in ['up', 'dw', 'lt', 'rt']:
                perform_swipe(cmd)
            elif cmd == 'cls':
                automator.clear_logs()
            elif cmd == 'exit':
                break
            
            # Element Interaction
            else:
                try:
                    index = int(cmd) - 1
                    if 0 <= index < len(elements):
                        el = elements[index]
                        x, y = bounds_to_center(el.get('bounds', '0,0,0,0'))
                        
                        if "EditText" in el.get('class', ''):
                            print(f"[+] Focused: {el.get('id', 'n/a')}")
                            automator.click(x, y)
                            time.sleep(0.5) 
                            text_to_type = input("[+] Enter text: ").strip()
                            if text_to_type:
                                automator.input_text(text_to_type)
                        else:
                            print(f"[+] Clicking: {el.get('text', 'element')}")
                            automator.click(x, y)
                    else:
                        print("[!] Invalid index.")
                        time.sleep(1)
                except ValueError:
                    print("[!] Unknown command.")
                    time.sleep(1)

            time.sleep(0.2)

        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"[!] Script Error: {str(e)}")
            time.sleep(2)

if __name__ == "__main__":
    main()
