# dmgbuild settings for tools/bundle-macos.sh: -D app=<.app> -D icon=<.icns> -D background=<.png>
import os.path

app = defines["app"]

format = "UDZO"
filesystem = "HFS+"
files = [app]
symlinks = {"Applications": "/Applications"}
icon = defines["icon"]
background = defines["background"]

# Finder counts the title bar in the height: 400 of content under it.
window_rect = ((200, 120), (640, 428))
default_view = "icon-view"
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
icon_size = 128
text_size = 13
icon_locations = {
    os.path.basename(app): (160, 190),
    "Applications": (480, 190),
}
