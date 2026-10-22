
try:
    from kivymd.toast import toast as tst
except:pass
import json
import threading
import ast
import queue
import os
import urllib3
import time
from kivy.metrics import dp
from kivy.app import App
from kivy.core.window import Window
from kivy.animation import Animation
from kivy.graphics import Color, RoundedRectangle, Line, Ellipse
from kivy.lang import Builder
from kivy.uix.popup import Popup
from kivy.uix.boxlayout import BoxLayout
from kivy.clock import Clock
from kivy.uix.label import Label
from kivy.utils import platform
from kivy.uix.widget import Widget
from kivy.uix.button import Button
from kivymd.app import MDApp
from kivymd.uix.filemanager import MDFileManager
from kivy.core.clipboard import Clipboard
from kivymd.uix.dialog import (
    MDDialog,
    MDDialogHeadlineText,
    MDDialogSupportingText,
    MDDialogButtonContainer,
)
from kivymd.uix.button import MDButton, MDButtonText
from kivy.uix.behaviors import ButtonBehavior
from kivy.properties import NumericProperty, ListProperty, StringProperty

from oscpy.client import OSCClient
from oscpy.server import OSCThreadServer

if platform == "android":
    from jnius import autoclass, JavaException, cast
    from android.permissions import request_permissions, Permission, check_permission
    from android.storage import primary_external_storage_path
    from android import mActivity

    Intent = autoclass('android.content.Intent')
    Settings = autoclass('android.provider.Settings')
    DocumentsContract = autoclass("android.provider.DocumentsContract")
    Uri = autoclass('android.net.Uri')
    Environment = autoclass('android.os.Environment')
    File = autoclass('java.io.File')
    PythonActivity = autoclass('org.kivy.android.PythonActivity')
    activity = PythonActivity.mActivity
    PowerManager = autoclass('android.os.PowerManager')  # Add this line
    Context = autoclass('android.content.Context')  # Add this line
    BuildVersion = autoclass('android.os.Build$VERSION')

    def request_android_permissions():
        print("🔵 Ejecutando request_android_permissions()")
        if BuildVersion.SDK_INT >= 30:
            request_all_files_access()
        else:
            request_read_write_access()
            
    def request_all_files_access():
        if not Environment.isExternalStorageManager():
            try:
                intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.setData(Uri.parse(f"package:{activity.getPackageName()}"))
                activity.startActivity(intent)
            except Exception as e:
                print(f"Error en requestAllFilesAccess: {e}")
                request_read_write_access()

    def request_read_write_access():
        permissions = [Permission.READ_EXTERNAL_STORAGE, Permission.WRITE_EXTERNAL_STORAGE]
        request_permissions(permissions, on_permission_callback)

    def on_permission_callback(permissions, results):
        if all(results):
            tst("Permisos otorgados")
        else:
            tst("Permisos no otorgados")

    def asks_permission_if_needed():
        print("🔵 Ejecutando asks_permission_if_needed()")
        """
        Solicita permisos para enviar notificaciones si es necesario.
        """
        permissions = [Permission.POST_NOTIFICATIONS]
        if not all(check_permission(p) for p in permissions):
            request_permissions(permissions)


    def acquire_wake_lock():
        context = activity.getApplicationContext()
        pm = context.getSystemService(Context.POWER_SERVICE)
        wake_lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.kivis.downfree:DownloadService")
        wake_lock.setReferenceCounted(False)
        if not wake_lock.isHeld():
            wake_lock.acquire()
        return wake_lock

    def release_wake_lock(wake_lock):
        if wake_lock and wake_lock.isHeld():
            wake_lock.release()

    def request_ignore_battery_optimizations():
        print("🔵 Ejecutando request_ignore_battery_optimizations()")
        try:
            pm = activity.getSystemService(activity.POWER_SERVICE)
            package_name = activity.getPackageName()
            if not pm.isIgnoringBatteryOptimizations(package_name):
                intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.setData(Uri.parse(f"package:{package_name}"))
                activity.startActivity(intent)
        except JavaException as e:
            tst(f"Error al solicitar la exclusión de optimización de batería: {str(e)}")

    Clock.schedule_once(lambda dt: request_android_permissions(), 0.1)
    Clock.schedule_once(lambda dt: asks_permission_if_needed(), 0.5)
    
    if BuildVersion.SDK_INT >= 28:
        Clock.schedule_once(lambda dt: request_ignore_battery_optimizations(), 1)
    
    def get_app_specific_storage_path():
        try:
            storage_dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if storage_dir:
                return storage_dir.getAbsolutePath()
        except Exception as e:
            print("Error obteniendo external dir:", e)
        return activity.getFilesDir().getAbsolutePath()
    
urllib3.disable_warnings()
import logging

# Oculta logs DEBUG de requests/urllib3
logging.getLogger("urllib3").setLevel(logging.WARNING)
logging.getLogger("http.client").setLevel(logging.WARNING)

Builder.load_string('''
#:import get_color_from_hex kivy.utils.get_color_from_hex

<PillButton>:
    background_color: 0, 0, 0, 0
    background_normal: ''
    size_hint: None, None
    height: dp(32)
    width: self.texture_size[0] + dp(24)
    color: 0.0, 0.85, 0.9, 0.8
    font_size: dp(12)
    ripple_color: 0, 0.85, 0.9, 0.2
    canvas.before:
        Color:
            rgba: app.button_bg
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(16)]
        Color:
            rgba: app.button_border
        Line:
            rounded_rectangle: self.x, self.y, self.width, self.height, dp(16)
            width: 1

<PillButtonRed>:
    background_color: 0, 0, 0, 0
    background_normal: ''
    size_hint: None, None
    height: dp(32)
    width: self.texture_size[0] + dp(24)
    color: 0.9, 0.3, 0.35, 1
    font_size: dp(12)
    ripple_color: 0.9, 0.3, 0.35, 0.2
    canvas.before:
        Color:
            rgba: app.button_bg
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(16)]
        Color:
            rgba: app.button_border
        Line:
            rounded_rectangle: self.x, self.y, self.width, self.height, dp(16)
            width: 1

<PillButtonGreen>:
    background_color: 0, 0, 0, 0
    background_normal: ''
    size_hint: None, None
    height: dp(28)
    width: self.texture_size[0] + dp(24)
    color: 0.2, 0.8, 0.4, 1
    font_size: dp(12)
    ripple_color: 0.2, 0.8, 0.4, 0.2
    canvas.before:
        Color:
            rgba: 0.12, 0.12, 0.14, 1
                                   
<SecondaryPillButton@Button>:
    background_color: 0, 0, 0, 0
    background_normal: ''
    size_hint: None, None
    height: dp(28)  # Un poco más bajo para que quepa bien dentro del input
    width: self.texture_size[0] + dp(20)
    color: 0.0, 0.85, 0.9, 0.8  # Color cian suave
    font_size: dp(11)
    bold: True
    canvas.before:
        Color:
            rgba: app.button_bg
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(8)]
        Color:
            rgba: app.button_border

<Downloader>:
    canvas.before:
        Color:
            rgba: app.bg_color
        Rectangle:
            pos: self.pos
            size: self.size
    orientation: 'vertical'
    padding: [dp(16), dp(12), dp(16), dp(16)]
    spacing: dp(12)
    
    # Header con titulo y estadisticas
    BoxLayout:
        orientation: 'vertical'
        size_hint_y: None
        height: dp(70)
        spacing: dp(4)
        
        BoxLayout:
            orientation: 'horizontal'
            size_hint_y: None
            height: dp(36)
            
            Label:
                text: 'Descargas'
                color: app.text_primary
                font_size: dp(24)
                bold: True
                halign: 'left'
                valign: 'middle'
                text_size: self.size
            
            # Boton info o menu
            MDIconButton:
                icon: "information"
                pos_hint: {"center_y": 0.5}
                icon_size: dp(22)
                theme_text_color: "Custom"
                text_color: app.text_secondary 
                on_release: root.mostrar_info()

            # NUEVO: Boton para cambiar de tema
            MDIconButton:
                icon: "weather-sunny" if app.theme_mode == "Dark" else "weather-night"
                pos_hint: {"center_y": 0.5}
                icon_size: dp(22)
                theme_text_color: "Custom"
                text_color: app.text_secondary
                on_release: app.toggle_theme()
                    
        # Estadisticas: activas, cola, completas
        BoxLayout:
            orientation: 'horizontal'
            size_hint_y: None
            height: dp(20)
            spacing: dp(4)
            
            Widget:
                id: proxy_indicator
                size_hint: None, None
                size: dp(8), dp(8)
                pos_hint: {"center_y": 0.5}
            
            Label:
                id: stats_label
                text: '0 activas  -  0 cola  -  0 completas'
                color: app.text_secondary
                font_size: dp(12)
                halign: 'left'
                valign: 'middle'
                text_size: self.size
                    
    # Input URL - Campo para pegar URLs
    BoxLayout:
        orientation: 'horizontal'
        size_hint_y: None
        height: dp(56)
        spacing: dp(8)
        
        BoxLayout:
            padding: [dp(4), 0, dp(8), 0]        
            canvas.before:
                Color:
                    rgba: app.input_bg_color
                RoundedRectangle:
                    pos: self.pos
                    size: self.size
                    radius: [dp(12)]
                Color:
                    rgba: app.input_border_color
                Line:
                    rounded_rectangle: self.x, self.y, self.width, self.height, dp(12)
                    width: 1
            
            TextInput:
                id: url_input
                hint_text: 'Pega la URL aqui...'
                hint_text_color: 0.5, 0.5, 0.55, 1
                background_color: 0, 0, 0, 0
                foreground_color: app.text_primary
                cursor_color: 0.0, 0.85, 0.9, 1
                font_size: dp(13)
                padding: [dp(16), dp(18), dp(16), dp(18)]
                multiline: False

            SecondaryPillButton:
                text: "PEGAR"
                pos_hint: {"center_y": 0.5}
                on_release: root.pegar_desde_portapapeles(self)
        
        
        MDIconButton:
            icon: "download"
            pos_hint: {"center_y": 0.5}
            icon_size: dp(26)
            theme_text_color: "Custom"
            text_color: app.brand_color
            md_bg_color: app.input_bg_color
            on_release: root.queue_download(url_input.text)
    
    # Barra de velocidad global y ETA
    BoxLayout:
        orientation: 'horizontal'
        size_hint_y: None
        height: dp(24)
        spacing: dp(8)
        
        Label:
            text: 'Velocidad global'
            color: 0.5, 0.5, 0.55, 1
            font_size: dp(11)
            halign: 'left'
            valign: 'middle'
            size_hint_x: None
            width: dp(90)
        
        Label:
            id: global_speed
            text: '0.00 MB/s'
            color: app.text_primary
            font_size: dp(12)
            bold: True
            halign: 'left'
            valign: 'middle'
            size_hint_x: None
            width: dp(80)
        
        Widget:
            size_hint_x: 1
        
        Label:
            text: 'ETA global'
            color: app.text_secondary
            font_size: dp(11)
            halign: 'right'
            valign: 'middle'
            size_hint_x: None
            width: dp(65)
        
        Label:
            id: global_eta
            text: '--'
            color: app.text_primary
            font_size: dp(12)
            bold: True
            halign: 'right'
            valign: 'middle'
            size_hint_x: None
            width: dp(50)
    
    # Botones de accion: Pausar, Reanudar, Limpiar, Ajustes
    ScrollView:
        do_scroll_y: False
        do_scroll_x: True
        size_hint_y: None
        height: dp(48)  # Un poco más alto que los botones para que no se corte la sombra/borde
        bar_width: 0  
                          
        BoxLayout:
            orientation: 'horizontal'
            size_hint_x: None
            width: self.minimum_width 
            spacing: dp(8)
            padding: [0, dp(4)]
            
            PillButton:
                text: 'Pausar'
                on_release: root.pause_all()
            
            PillButton:
                text: 'Reanudar'
                on_release: root.resume_all()
            
            PillButtonRed:
                text: 'Limpiar'
                on_release: root.clear_completed()
            
            PillButton:
                text: 'Ajustes'
                on_release: app.file_manager_open()
            
            PillButton:
                id: max_dl_btn
                text: 'Máx activas: 1'
                on_release: root.cycle_max_downloads()
    
    # Label de estado (oculto por defecto)
    Label:
        id: status_label
        text: ''
        font_size: dp(12)
        color: app.text_secondary
        halign: 'center'
        size_hint_y: None
        height: dp(0)
    
    # Lista de descargas
    ScrollView:
        do_scroll_x: False
        bar_width: dp(3)
        bar_color: 0.3, 0.3, 0.35, 0.4
        
        GridLayout:
            id: download_list
            cols: 1
            spacing: dp(8)
            size_hint_y: None
            height: self.minimum_height
            padding: [0, dp(8)]
    
    # Ruta seleccionada (footer)
    Label:
        id: status_label_2
        text: ''
        font_size: dp(11)
        color: 0.4, 0.4, 0.45, 1
        halign: 'center'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(32) if self.text else dp(8)
''')

if platform == "android":
    SAVE_PATH = os.path.join(get_app_specific_storage_path(), "downloads.json")
else: 
    SAVE_PATH = 'Downloads_C/' + "downloads.json"

status_path = os.path.join(os.path.dirname(SAVE_PATH), "download_status.json")
selected_path = os.path.join(os.path.dirname(SAVE_PATH))

from kivymd.uix.behaviors import CircularRippleBehavior

class PillButton(CircularRippleBehavior, Button):
    pass

class PillButtonRed(CircularRippleBehavior, Button):
    pass

class PillButtonGreen(CircularRippleBehavior, Button):
    pass

from kivy.properties import NumericProperty
class DownloadCard(ButtonBehavior, BoxLayout):
    # Declaramos la propiedad formalmente
    canvas_opacity = NumericProperty(1)

    def on_release(self):
        # Animación de feedback visual (parpadeo)
        anim = Animation(canvas_opacity=0.2, duration=0.5) + Animation(canvas_opacity=1, duration=0.1)
        anim.start(self)

# Barra de progreso horizontal estilo moderno
class CustomProgressBar(Widget):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.progress = 0
        self.size_hint = (1, None)
        self.height = dp(4)
        
        with self.canvas:
            # Fondo de la barra
            Color(0.15, 0.15, 0.18, 1)
            self.bg_rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(2)])
            # Barra de progreso (cyan/turquesa)
            Color(0.0, 0.75, 0.8, 1)
            self.progress_rect = RoundedRectangle(pos=self.pos, size=(0, self.height), radius=[dp(2)])
        
        self.bind(pos=self.update_bar, size=self.update_bar)
    
    def update_bar(self, *args):
        self.bg_rect.pos = self.pos
        self.bg_rect.size = self.size
        self.progress_rect.pos = self.pos
        progress_width = self.width * (self.progress / 100)
        self.progress_rect.size = (progress_width, self.height)
    
    def set_progress(self, progress):
        self.progress = min(100, max(0, progress))
        self.update_bar()

class Downloader(BoxLayout, MDApp):
    # --- PROPIEDADES REACTIVAS DE TEMA ---
    theme_mode = StringProperty("Dark")
    
    # Paleta de colores inicial (Oscuro - tus colores actuales)
    brand_color = ListProperty([0.0, 0.75, 0.8, 1])
    bg_color = ListProperty([0.04, 0.04, 0.05, 1])
    card_color = ListProperty([0.08, 0.08, 0.1, 1])
    input_bg_color = ListProperty([0.1, 0.1, 0.12, 1])
    input_border_color = ListProperty([0.2, 0.2, 0.24, 1])
    text_primary = ListProperty([1, 1, 1, 1])
    text_secondary = ListProperty([0.5, 0.5, 0.55, 1])
    button_bg = ListProperty([0.12, 0.12, 0.14, 1])
    button_border = ListProperty([0.25, 0.25, 0.28, 1])

    stop_event = threading.Event()
    wake_lock = None
    dialog = None

    def on_start(self):
        self.load_downloads()
        
    def __init__(self, **kwargs):
        super(Downloader, self).__init__(**kwargs)  # Llamada al constructor de BoxLayout
        self.client = OSCClient(b'127.0.0.1', 3000)  # Inicialización correcta de client
        self.service = None
        self.stop_event.clear()
        self.active_downloads = [] 

        #🔹 Configurar el cliente OSC
        self.server = OSCThreadServer()
        self.server.listen(
            address=b'127.0.0.1',
            port=3002,
            default=True,
        )
        self.server.bind(b'/download_status', self.update_status)
        self.server.bind(b"/complete_download", self.complete_download)
        self.server.bind(b'/service_log', self.receive_service_log)
        self.server.bind(b'/status_label', self.status_label)
        self.server.bind(b'/label_text', self.label_text)
        self.server.bind(b'/mostrar_error', mostrar_error)
        self.server.bind(b'/tst_view', tst_view)

        self.proxy_color = None
        self.proxy_circle = None
        self.bind(size=self._draw_proxy, pos=self._draw_proxy)

        self._global_speed_bps_smooth = 0.0
        self._global_eta_seconds_smooth = None

        self.manager_open = False
        self.file_manager = MDFileManager(
            exit_manager=self.exit_manager, 
            select_path=self.select_path,
            background_color_toolbar="blue",
            show_hidden_files = False,
            search = "dirs"
        )

        Clock.schedule_interval(self.update_stats_label, 1.5)

    def toggle_theme(self):
        """Alterna los colores y guarda la elección."""
        if self.theme_mode == "Dark":
            self.apply_theme_logic("Light")
        else:
            self.apply_theme_logic("Dark")
        
        self.save_config() # <--- Guardar persistencia

    def apply_theme_logic(self, mode):
        """Aplica los colores según el modo seleccionado sin guardarlos."""
        self.theme_mode = mode
        if mode == "Light":
            self.theme_cls.theme_style = "Light"
            self.brand_color = [0.0, 0.6, 0.65, 1]
            self.bg_color = [0.95, 0.95, 0.97, 1]
            self.card_color = [1, 1, 1, 1]
            self.input_bg_color = [0.9, 0.9, 0.93, 1]
            self.input_border_color = [0.8, 0.8, 0.85, 1]
            self.text_primary = [0.1, 0.1, 0.1, 1]
            self.text_secondary = [0.4, 0.4, 0.45, 1]
            self.button_bg = [0.9, 0.9, 0.93, 1]
            self.button_border = [0.75, 0.75, 0.8, 1]
        else:
            self.theme_cls.theme_style = "Dark"
            self.brand_color = [0.0, 0.75, 0.8, 1]
            self.bg_color = [0.04, 0.04, 0.05, 1]
            self.card_color = [0.08, 0.08, 0.1, 1]
            self.input_bg_color = [0.1, 0.1, 0.12, 1]
            self.input_border_color = [0.2, 0.2, 0.24, 1]
            self.text_primary = [1, 1, 1, 1]
            self.text_secondary = [0.5, 0.5, 0.55, 1]
            self.button_bg = [0.12, 0.12, 0.14, 1]
            self.button_border = [0.25, 0.25, 0.28, 1]
        
        self.update_dynamic_widgets_theme()

    def update_dynamic_widgets_theme(self):
        """Actualiza el color de los widgets generados en Python (las tarjetas)."""
        for child in self.ids.download_list.children:
            if hasattr(child, 'bg_color'):
                # Mantiene la opacidad original de la animación del botón
                child.bg_color.rgba = [*self.card_color[:3], child.bg_color.a]
            
            if hasattr(child, 'ids'):
                ids = child.ids
                if 'label_text' in ids: ids['label_text'].color = self.text_primary
                if 'label_percent' in ids: ids['label_percent'].color = self.text_primary
                if 'label_text_2' in ids: ids['label_text_2'].color = self.text_secondary
                if 'label_size' in ids: ids['label_size'].color = self.text_secondary
                if 'label_speed' in ids: ids['label_speed'].color = self.text_secondary
                if 'label_eta' in ids: ids['label_eta'].color = self.text_secondary


    def mostrar_info(self):
        if not self.dialog:
            self.dialog = MDDialog(
                MDDialogHeadlineText(
                    text="Down Free v7.8.8",
                    halign="left",
                    theme_text_color="Custom",
                    text_color=[1, 1, 1, 1],
                ),
                MDDialogSupportingText(
                    text="Gestor de descargas para uso sin consumo de megas. Perteneciente al servicio Uploader Free. Para más información, visita nuestro canal de Telegram @UploaderFreeChannel.",
                    halign="left",
                    theme_text_color="Custom",
                    text_color=[0.6, 0.6, 0.65, 1],
                ),
                MDDialogButtonContainer(
                    Widget(),
                    MDButton(
                        MDButtonText(
                            text="CERRAR",
                            theme_text_color="Custom",
                            text_color=[0.0, 0.85, 0.9, 1],
                        ),
                        style="text",
                        on_release=lambda x: self.dialog.dismiss(),
                    ),
                    spacing="8dp",
                ),
                # ESTAS DOS LÍNEAS SON LA CLAVE:
                theme_bg_color="Custom",
                md_bg_color=[0, 0, 0, 1], # Negro puro
            )
        self.dialog.open()

    def pegar_desde_portapapeles(self, instance):
        # Obtiene el texto del portapapeles
        texto_portapapeles = Clipboard.paste()
        if texto_portapapeles:
            # 'self.ids.url_input' sería el id de tu caja de texto en el archivo .kv
            self.ids.url_input.text = texto_portapapeles

    def set_max_downloads(self, value):
        try:
            self.client.send_message(b"/set_max_downloads", [str(value).encode()])
        except Exception as e:
            print(f"Error ajustando límite: {e}")
                
    def cycle_max_downloads(self):
        # Obtiene el número actual del texto del botón
        current = int(self.ids.max_dl_btn.text.split(': ')[1])
        # Rota entre 1, 2 y 3
        next_val = current + 1 if current < 3 else 1
        # Actualiza el botón y envía la instrucción al servicio backend
        self.ids.max_dl_btn.text = f'Máx activas: {next_val}'
        self.set_max_downloads(str(next_val))

    def update_stats_label(self, dt):
        completas = 0
        en_progreso = 0
        
        for child in self.ids.download_list.children:
            if not hasattr(child, 'ids') or 'label_text_2' not in child.ids:
                continue
            estado = child.ids['label_text_2'].text.lower()
            if 'finalizada' in estado or 'completa' in estado:
                completas += 1
            elif 'pausado' not in estado:
                en_progreso += 1
                
        # Obtenemos el límite del nuevo botón (ver solución 2 abajo)
        try:
            max_activas = int(self.ids.max_dl_btn.text.split(': ')[1])
        except:
            max_activas = 1
            
        # Calculamos cuáles están realmente activas y cuáles en cola
        activas_reales = min(en_progreso, max_activas)
        en_cola = max(0, en_progreso - max_activas)
            
        self.ids.stats_label.text = f'{activas_reales} activas  -  {en_cola} cola  -  {completas} completas'
        
    def _draw_proxy(self, *args):
        """Dibuja el círculo verde y lanza la animación de pulso."""
        indicator = self.ids.proxy_indicator
        indicator.canvas.before.clear()
        with indicator.canvas.before:
            self.proxy_color = Color(0, 1, 0, 1)  # verde brillante
            self.proxy_circle = Ellipse(pos=indicator.pos, size=indicator.size)

        indicator.bind(pos=self._update_proxy_circle, size=self._update_proxy_circle)
        self._start_pulse()

    def _update_proxy_circle(self, instance, value):
        """Mantiene el círculo en la posición/tamaño correctos."""
        if self.proxy_circle:
            self.proxy_circle.pos = instance.pos
            self.proxy_circle.size = instance.size

    def _start_pulse(self):
        """Crea animación infinita de parpadeo."""
        if not self.proxy_color:
            return
        anim = Animation(a=0.6, duration=1.6) + Animation(a=1, duration=0.6)
        anim.repeat = True
        anim.start(self.proxy_color)
        
    def build(self):
        self.start_service()
        if platform == "win":
            Window.size = (420, 667)
        return self 

    def file_manager_open(self):
        """Abre el gestor de archivos nativo para seleccionar una carpeta."""
        directory = primary_external_storage_path() if platform == "android" else os.path.expanduser("~")
        if not os.path.exists(directory):
            print(f"⚠️ La ruta {directory} no existe, se usará la ruta de descarga de la app.")
            directory = os.path.join(get_app_specific_storage_path())  # Usa el directorio predeterminado
        self.file_manager.show(directory)
        self.manager_open = True

    def select_path(self, path: str):
        self.exit_manager()
        self.ids.status_label_2.text = f"Ruta: {path}"
        self.save_config()
        try:
            self.client.send_message(b"/selected_path", [path.encode()])
            print(f"✅ Ruta guardada y tema preservado: {path}")
        except Exception as e:
            print(f"❌ Error guardando la ruta en JSON: {e}")

    def exit_manager(self, *args):
        '''Called when the user reaches the root of the directory tree.'''
        self.manager_open = False
        self.file_manager.close()

    def save_config(self):
        """Guarda la ruta y el tema actual en el archivo JSON."""
        json_file = os.path.join(os.path.dirname(SAVE_PATH), "download_path.json")
        # Obtenemos la ruta actual del label para no perderla
        current_path = self.ids.status_label_2.text.replace("Ruta: ", "")
        
        config_data = {
            "selected_path": current_path,
            "theme_mode": self.theme_mode
        }
        
        try:
            with open(json_file, "w") as f:
                json.dump(config_data, f, indent=4)
        except Exception as e:
            print(f"❌ Error al guardar configuración: {e}")

    def receive_service_log(self, message):
        print("🔹 Log del servicio:", message.decode("utf-8"))
    
    def label_text(self, message):
        try:
            data = json.loads(message.decode("utf-8"))
            filename = data.get("filename")
            text = data.get("text", "")

            if not filename:
                return  # opcional: ignorar si no viene filename

            for child in self.ids.download_list.children:
                if getattr(child, "filename", None) == filename:
                    if 'label_text_2' in child.ids:
                        child.ids['label_text_2'].text = text
                    break

        except Exception as e:
            print("Error en label_text:", e)
            
    def status_label(self, message):
        self.ids.status_label.text = message.decode("utf-8")

    def sizeof_fmt(self,num, suffix='B'):
        for unit in ['', 'Ki', 'Mi', 'Gi', 'Ti', 'Pi', 'Ei', 'Zi']:
            if abs(num) < 1024.0:
                return "%3.2f%s%s" % (num, unit, suffix)
            num /= 1024.0
        return "%.2f%s%s" % (num, 'Yi', suffix)


    def queue_download(self, u):
        """Envia la URL al servicio para iniciar la descarga."""
        try:
            if u:
                try:
                    print("Adquiriendo Wake_look")
                    if platform == "android":
                        if not self.wake_lock:
                            self.wake_lock = acquire_wake_lock()
                        elif not self.wake_lock.isHeld():
                            self.wake_lock.acquire()
                except Exception as e:
                    print("Error adquiriendo Wake_look:", e)
                self.client.send_message(b"/start_download", [u.encode()])
                url = ast.literal_eval(u)
                filename = url['fn']
                filet = url['fn']
                if len(filet) > 25:
                    filet = filename[:20] + "." + filename[-5:]

                # Obtener el tamaño total si está disponible
                try:
                    total_size = int(url.get('fs', 0))
                except (ValueError, TypeError):
                    total_size = 0
                    
                self.add_download(filename, "/", filet, url_data=u, total_size=total_size)
                self.ids.url_input.text = ''
        except Exception as e:
            print("Error queuing download:", e)
            
    def add_download(self, filename, file_path, display_name=None, save_to_disk=True, url_data=None, total_size=0):
        """Crea y anade un widget de descarga a la lista con estilo moderno."""
        def add_download_widget(dt):
            try:
                if filename in [child.filename for child in self.ids.download_list.children]:
                    print(f"Ya existe un widget para {filename}")
                    return
                
                # Card principal
                download_item = DownloadCard(
                    orientation="vertical",
                    size_hint_y=None,
                    height=dp(110),
                    padding=[dp(14), dp(12), dp(14), dp(12)],
                    spacing=dp(6)
                )
                
                download_item.bind(on_release=self.abrir_archivo_descargado)

                # Fondo oscuro con bordes suaves
                with download_item.canvas.before:
                    download_item.bg_color = Color(*self.card_color[:3], download_item.canvas_opacity)
                    download_item.rect = RoundedRectangle(
                        pos=download_item.pos, 
                        size=download_item.size, 
                        radius=[dp(12)]
                    )

                download_item.bind(pos=self.update_rect, size=self.update_rect)
                download_item.bind(canvas_opacity=lambda instance, value: setattr(instance.bg_color, 'a', value))

                # Fila superior: nombre + porcentaje
                top_row = BoxLayout(
                    orientation="horizontal",
                    size_hint_y=None,
                    height=dp(22)
                )
                
                label_filename = Label(
                    text=display_name or filename,
                    color=self.text_primary,
                    font_size=dp(14),
                    bold=True,
                    halign="left",
                    valign="middle",
                    text_size=(None, None),
                    shorten=True,
                    shorten_from='right'
                )
                label_filename.bind(size=lambda *x: setattr(label_filename, 'text_size', (label_filename.width, None)))
                
                label_percent = Label(
                    text="0%",
                    color=self.text_primary,
                    font_size=dp(14),
                    bold=True,
                    halign="right",
                    valign="middle",
                    size_hint_x=None,
                    width=dp(45)
                )
                
                top_row.add_widget(label_filename)
                top_row.add_widget(label_percent)
                
                # Segunda fila: fuente + ETA
                info_row = BoxLayout(
                    orientation="horizontal",
                    size_hint_y=None,
                    height=dp(16)
                )
                
                label_source = Label(
                    text="Pendiente...",
                    color=self.text_secondary,
                    font_size=dp(11),
                    halign="left",
                    valign="middle"
                )
                label_source.bind(size=lambda *x: setattr(label_source, 'text_size', (label_source.width, None)))
                
                label_eta = Label(
                    text="ETA --",
                    color=self.text_secondary,
                    font_size=dp(11),
                    halign="right",
                    valign="middle",
                    size_hint_x=None,
                    width=dp(70)
                )
                
                info_row.add_widget(label_source)
                info_row.add_widget(label_eta)
                
                # Barra de progreso
                progress_bar = CustomProgressBar()
                
                # Fila de datos: tamano + velocidad
                data_row = BoxLayout(
                    orientation="horizontal",
                    size_hint_y=None,
                    height=dp(16)
                )
                
                if total_size > 0:
                    formatted_total = self.sizeof_fmt(total_size)
                    size_text = f"0.00 B / {formatted_total}"
                else:
                    size_text = "0 MB / 0 MB"

                label_size = Label(
                    text=size_text,
                    color=self.text_secondary,
                    font_size=dp(11),
                    halign="left",
                    valign="middle"
                )
                label_size.bind(size=lambda *x: setattr(label_size, 'text_size', (label_size.width, None)))
                
                label_speed = Label(
                    text="0 KB/s",
                    color=(0.5, 0.5, 0.55, 1),
                    font_size=dp(11),
                    halign="right",
                    valign="middle",
                    size_hint_x=None,
                    width=dp(80)
                )
                
                data_row.add_widget(label_size)
                data_row.add_widget(label_speed)
                
                # Fila de botones
                buttons_row = BoxLayout(
                    orientation="horizontal",
                    size_hint_y=None,
                    height=dp(28),
                    spacing=dp(10)
                )
                
                # Boton Pausar individual
                pause_btn = Button(
                    text="Pausar",
                    background_color=(0, 0, 0, 0),
                    background_normal='',
                    color=self.text_secondary,
                    font_size=dp(12),
                    size_hint=(None, None),
                    height=dp(28),
                    width=dp(60)
                )
                pause_btn.bind(on_release=lambda instance, fn=filename, di=download_item, pb=pause_btn: self.toggle_pause_download(fn, di, pb))
                
                retry_btn = PillButtonGreen(
                    text="Reintentar"
                )

                def send_to_service(instance):
                    if url_data:
                        print(f"Reenviando {filename} al servicio...")
                        label_source.text = "Reintentando..."
                        # Opcional: Re-activar Wake Lock si es Android
                        if platform == "android":
                            if not self.wake_lock: self.wake_lock = acquire_wake_lock()
                            if not self.wake_lock.isHeld(): self.wake_lock.acquire()
                        
                        # Enviamos el mensaje al servicio
                        self.client.send_message(b"/start_download", [url_data.encode()])

                retry_btn.bind(on_release=send_to_service)

                # Boton Eliminar (rojo)
                cancel_btn = Button(
                    text="Eliminar",
                    background_color=(0, 0, 0, 0),
                    background_normal='',
                    color=(0.9, 0.35, 0.4, 1),
                    font_size=dp(12),
                    size_hint=(None, None),
                    height=dp(28),
                    width=dp(70)
                )
                cancel_btn.bind(on_release=lambda instance: self.cancel_download(filename, download_item))
                
                buttons_row.add_widget(pause_btn)
                buttons_row.add_widget(cancel_btn)
                buttons_row.add_widget(retry_btn)
                buttons_row.add_widget(Widget())  # Spacer

                # Agregar todos los elementos
                download_item.add_widget(top_row)
                download_item.add_widget(info_row)
                download_item.add_widget(progress_bar)
                download_item.add_widget(data_row)
                download_item.add_widget(buttons_row)

                download_item.filename = filename
                download_item.file_path = file_path
                download_item.is_paused = False
# --- NUEVAS LÍNEAS PARA EVITAR EL ERROR ---
                download_item._total_bytes = total_size
                download_item._downloaded_bytes = 0
                download_item._current_speed_bps = 0

                download_item.ids = {
                    'label_text': label_filename,
                    'label_text_2': label_source,
                    'label_percent': label_percent,
                    'label_eta': label_eta,
                    'label_size': label_size,
                    'label_speed': label_speed,
                    'progress_circle': progress_bar,
                    'pause_btn': pause_btn,
                    'retry_btn': retry_btn
                }

                self.ids.download_list.add_widget(download_item)

                if save_to_disk:
                    self.active_downloads.append({
                        "url_data": url_data,
                        "filename": filename,
                        "file_path": file_path,
                        "display_name": display_name,
                        "total_size": total_size
                    })
                    self.save_downloads()
            except Exception as e:
                print(f"Error adding download widget: {e}")
        Clock.schedule_once(add_download_widget, 0)

    def abrir_archivo_descargado(self, instance):
        """Abre el archivo descargado si la descarga está al 100%."""
        try:
            # Comprobar que ya terminó
            if 'label_percent' not in instance.ids or instance.ids['label_percent'].text != '100%':
                return

            filename = instance.filename
            saved_path = ""
            
            # Buscar la ruta real en el JSON de configuración
            json_file = os.path.join(os.path.dirname(SAVE_PATH), "download_path.json")
            if os.path.exists(json_file):
                with open(json_file, "r") as f:
                    data = json.load(f)
                    saved_path = data.get("selected_path", "")
                    
            if saved_path:
                base_path = os.path.join(saved_path, "Down_Free")
            else:
                if platform == "android":
                    base_path = os.path.join(get_app_specific_storage_path(), "Down_Free")
                else:
                    base_path = os.path.join("Downloads_C", "Down_Free")

            final_path = os.path.join(base_path, "Downloads", filename)

            if not os.path.exists(final_path):
                if platform == "android": tst("El archivo no existe.")
                print(f"No se encontró el archivo en: {final_path}")
                return

            # Abrir archivo según plataforma
            if platform == "win":
                os.startfile(final_path)
                
            elif platform == "android":
                from jnius import autoclass
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                activity = PythonActivity.mActivity

                Intent = autoclass('android.content.Intent')
                Uri = autoclass('android.net.Uri')
                File = autoclass('java.io.File')
                BuildVersion = autoclass("android.os.Build$VERSION")
                StrictMode = autoclass('android.os.StrictMode')
                VmPolicyBuilder = autoclass('android.os.StrictMode$VmPolicy$Builder')

                # 1. Bypass de seguridad para compartir rutas file://
                StrictMode.setVmPolicy(VmPolicyBuilder().build())

                # 1. Asegurar que el archivo existe y es legible
                file_obj = File(final_path)
                if not file_obj.exists():
                    tst("El archivo no se encuentra en la ruta pública.")
                    print(f"Ruta intentada: {final_path}")
                    return

                extension = os.path.splitext(filename)[1][1:].lower()
                
                # 2. Configuración específica para APK
                if extension == 'apk':
                    if BuildVersion.SDK_INT >= 26:
                        if not activity.getPackageManager().canRequestPackageInstalls():
                            tst("Autoriza la instalación de apps.")
                            Settings = autoclass('android.provider.Settings')
                            intent_settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            intent_settings.setData(Uri.parse(f"package:{activity.getPackageName()}"))
                            activity.startActivity(intent_settings)
                            return
                    mime_type = "application/vnd.android.package-archive"
                else:
                    MimeTypeMap = autoclass('android.webkit.MimeTypeMap')
                    mime_type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    mime_type = mime_type if mime_type else "*/*"

                # 3. Creación del Intent corregido
                uri = Uri.fromFile(file_obj)
                intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, mime_type)
                
                # Flags CRUCIALES para que Android no rechace la petición
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activity.startActivity(intent)

        except Exception as e:
            print(f"Error intentando abrir el archivo: {e}")
            if platform == "android":
                tst("Error al abrir o no hay aplicación disponible.")

    def toggle_pause_download(self, filename, download_item, pause_btn):
        """Pausa o reanuda una descarga individual."""
        try:
            if not hasattr(download_item, 'is_paused'):
                download_item.is_paused = False
            
            if download_item.is_paused:
                # Reanudar
                self.client.send_message(b"/resume_download", [filename.encode()])
                download_item.is_paused = False
                pause_btn.text = "Pausar"
                pause_btn.color = self.text_secondary
                download_item.ids['label_text_2'].text = "Descargando..."
            else:
                # Pausar
                self.client.send_message(b"/pause_download", [filename.encode()])
                download_item.is_paused = True
                download_item._current_speed_bps = 0
                self.recalculate_global_stats()
                pause_btn.text = "Reanudar"
                pause_btn.color = (0, 0.85, 0.9, 1)
                download_item.ids['label_text_2'].text = "Pausado"
        except Exception as e:
            print(f"Error toggle pause: {e}")
    
    def pause_all(self):
        """Pausa todas las descargas activas."""
        try:
            self.client.send_message(b"/pause_all", [])
            # Actualizar UI para mostrar estado pausado
            for child in self.ids.download_list.children:
                if hasattr(child, 'ids') and 'label_text_2' in child.ids:
                    status = child.ids['label_text_2'].text
                    if 'Descargando' in status or status == "Descargando...":
                        child.ids['label_text_2'].text = "Pausado"
                        child.is_paused = True
                        if 'pause_btn' in child.ids:
                            btn = child.ids['pause_btn']
                            btn.text = "Reanudar"
                            btn.color = (0, 0.85, 0.9, 1)
        except Exception as e:
            print(f"Error pausando descargas: {e}")
    
    def resume_all(self):
        """Reanuda todas las descargas pausadas."""
        try:
            self.client.send_message(b"/resume_all", [])
            # Actualizar UI para mostrar estado descargando
            for child in self.ids.download_list.children:
                if hasattr(child, 'ids') and 'label_text_2' in child.ids:
                    if 'Pausado' in child.ids['label_text_2'].text:
                        child.ids['label_text_2'].text = "Descargando..."
                        child.is_paused = False
                        if 'pause_btn' in child.ids:
                            btn = child.ids['pause_btn']
                            btn.text = "Pausar"
                            btn.color = self.text_secondary
        except Exception as e:
            print(f"Error reanudando descargas: {e}")
    
    def clear_completed(self):
        """Limpia las descargas completadas de la lista."""
        try:
            to_remove = []
            children_copy = list(self.ids.download_list.children)
            
            for child in children_copy:
                if hasattr(child, 'filename'):
                    is_complete = False
                    # Verificar por porcentaje
                    if hasattr(child, 'ids') and 'label_percent' in child.ids:
                        percent_text = child.ids['label_percent'].text
                        if '100' in percent_text:
                            is_complete = True
                    # Verificar por estado
                    if hasattr(child, 'ids') and 'label_text_2' in child.ids:
                        status_text = child.ids['label_text_2'].text.lower()
                        if 'finalizada' in status_text or 'completa' in status_text:
                            is_complete = True
                    if is_complete:
                        to_remove.append(child)
            
            for item in to_remove:
                self.ids.download_list.remove_widget(item)
                self.active_downloads = [d for d in self.active_downloads if d.get("filename") != item.filename]
            
            STATUS_PATH = os.path.join(os.path.dirname(SAVE_PATH), "download_status.json")
            try:
                if os.path.exists(STATUS_PATH):
                    with open(STATUS_PATH, "r") as f:
                        try:
                            status_data = json.load(f)
                        except json.JSONDecodeError:
                            status_data = {}
                    for item in to_remove:
                        status_data.pop(item.filename, None)
                    with open(STATUS_PATH, "w") as f:
                        json.dump(status_data, f, indent=4)
            except Exception as e:
                print(f"Error limpiando download_status.json: {e}")
                
            self.save_downloads()
            
            # Resetear velocidad global si no hay descargas activas
            active_count = len([c for c in self.ids.download_list.children if hasattr(c, 'ids')])
            if active_count == 0:
                for child in self.ids.download_list.children:
                    child._current_speed_bps = 0
                self.ids.global_speed.text = "0.00 MB/s"
                self.ids.global_eta.text = "--"
        except Exception as e:
            print(f"Error limpiando descargas: {e}")

    def update_rect(self, instance, value):
        """Actualiza el tamaño y la posición del Rectangle cuando cambia el BoxLayout."""
        instance.rect.pos = instance.pos
        instance.rect.size = instance.size

    def cancel_download(self, filename, download_item):
        """Cancela la descarga en progreso y elimina el widget."""
        try:
            self.client.send_message(b"/cancel_download", [filename.encode()])
            self.ids.download_list.remove_widget(download_item)
            self.active_downloads = [d for d in self.active_downloads if d.get("filename") != filename]
            self.save_downloads()
            STATUS_PATH = os.path.join(os.path.dirname(SAVE_PATH), "download_status.json")
            if os.path.exists(STATUS_PATH):
                with open(STATUS_PATH, "r") as f:
                    try:
                        status_data = json.load(f)
                    except json.JSONDecodeError:
                        status_data = {}
                if filename in status_data:
                    del status_data[filename]
                    # Guardamos el archivo actualizado sin esa entrada
                    with open(STATUS_PATH, "w") as f:
                        json.dump(status_data, f, indent=4)
                    print(f"Estado de {filename} eliminado de download_status.json")
        except Exception as e:
            print(f"Error cancelando la descarga: {e}")

    
    def update_status(self, message):
        """Recibe actualizaciones del servicio y muestra progreso con estilo moderno."""
        try:
            message = json.loads(message.decode("utf-8"))
            filename = message.get("filename", "Desconocido")
            downloaded = int(message.get("downloaded", 0))
            total_length = int(message.get("total_length", 1))
        except (json.JSONDecodeError, ValueError, TypeError):
            print("Error procesando mensaje de estado.")
            return

        download_item = next(
            (c for c in self.ids.download_list.children if getattr(c, 'filename', None) == filename), None
        )

        if not download_item:
            return
        
        # Si esta pausado, no actualizar la UI
        if getattr(download_item, 'is_paused', False):
            return

        now = time.time()

        if not hasattr(download_item, "_last_time") or downloaded < getattr(download_item, "_last_downloaded", 0):
            # Si es nuevo O si los bytes descargados bajaron (reinicio por error de red)
            download_item._last_time = now
            download_item._last_downloaded = downloaded
            download_item._velocidad_bps = 0.0
            download_item._eta_promedio = None
            # Forzamos que diff sea 0 en este ciclo para evitar saltos
            diff = 0
            elapsed = 0.001
        else:
            elapsed = now - download_item._last_time
            diff = downloaded - download_item._last_downloaded

        if elapsed > 0.1:
            # 1. Calcular velocidad instantánea
            instant_bps = max(0.0, diff / elapsed)
            
            # 2. Suavizado (EMA)
            alpha_vel = 0.1 # Ajustado para ser estable pero reactivo
            download_item._velocidad_bps = (
                (download_item._velocidad_bps * (1 - alpha_vel)) + (instant_bps * alpha_vel)
            )
            
            # IMPORTANTE: Guardar para el siguiente ciclo
            download_item._last_time = now
            download_item._last_downloaded = downloaded

        velocidad_bps = download_item._velocidad_bps
        download_item._current_speed_bps = velocidad_bps
        download_item._total_bytes = total_length
        download_item._downloaded_bytes = downloaded


        # Calcular ETA promedio
        restante = max(total_length - downloaded, 0)
        if velocidad_bps > 1024: # Solo calculamos ETA si la velocidad es > 1KB/s
            eta_segundos = restante / velocidad_bps
            alpha_eta = 0.1
            if download_item._eta_promedio is None:
                download_item._eta_promedio = eta_segundos
            else:
                download_item._eta_promedio = (
                    (download_item._eta_promedio * (1 - alpha_eta)) + (eta_segundos * alpha_eta)
                )

            # Formatear ETA de forma legible
            e = int(download_item._eta_promedio)
            if e < 60:
                eta_text = f"ETA {e}s"
            elif e < 3600:
                eta_text = f"ETA {e//60}m {e%60}s"
            else:
                eta_text = f"ETA {e//3600}h {(e%3600)//60}m"
        else:
            eta_text = "ETA --"

        # Mostrar velocidad en KB/s o MB/s
        if velocidad_bps < 1024 * 1024:
            velocidad_str = f"{velocidad_bps / 1024:.1f} KB/s"
        else:
            velocidad_str = f"{velocidad_bps / (1024 * 1024):.2f} MB/s"

        parte = self.sizeof_fmt(downloaded)
        total = self.sizeof_fmt(total_length)
        porc_float = (downloaded / total_length) * 100
        porc_float = max(0.0, min(100.0, porc_float))

        porc_texto = int(porc_float)

        # Actualizar la UI con el nuevo estilo
        def update_ui(dt):
            ids = download_item.ids
            if 'label_percent' in ids: ids["label_percent"].text = f"{porc_texto}%"
            if 'label_text_2' in ids:
                # No sobrescribir si ya está uniendo partes o si ya completó el 100% de la descarga
                if porc_texto < 100 and ids["label_text_2"].text != "Uniendo partes...":
                    ids["label_text_2"].text = "Descargando..."
            if 'label_eta' in ids: ids["label_eta"].text = eta_text
            if 'label_size' in ids: ids["label_size"].text = f"{parte} / {total}"
            if 'label_speed' in ids: ids["label_speed"].text = velocidad_str
            
            # Barra de progreso
            if 'progress_circle' in ids:
                ids["progress_circle"].set_progress(porc_float)

            self.recalculate_global_stats()
            
        Clock.schedule_once(update_ui)

    def recalculate_global_stats(self):
        total_speed_bps_instant = 0.0
        total_remaining_bytes = 0
        
        for child in self.ids.download_list.children:
            # Solo sumamos si el hilo está realmente descargando
            is_active = (
                hasattr(child, '_current_speed_bps') and 
                not getattr(child, 'is_paused', False) and
                getattr(child, 'status', '') != 'error' # Si tienes este atributo
            )
            
            if is_active:
                # Si el porcentaje es 100, la velocidad debe ser 0
                try:
                    p = int(child.ids['label_percent'].text.replace('%', ''))
                except: p = 0

                if p < 100:
                    total_speed_bps_instant += child._current_speed_bps
                    restante = child._total_bytes - child._downloaded_bytes
                    total_remaining_bytes += max(0, restante)

        # --- Suavizado de Velocidad Global ---
        # alpha controla la suavidad: 0.1 es lento/fluido, 0.8 es rápido/nervioso
        alpha_vel = 0.1
        self._global_speed_bps_smooth = (
            (self._global_speed_bps_smooth * (1 - alpha_vel)) + (total_speed_bps_instant * alpha_vel)
        )
        
        speed_to_show = self._global_speed_bps_smooth

        if speed_to_show < 1024:
            self.ids.global_speed.text = f"{speed_to_show:.1f} B/s"
        elif speed_to_show < 1024 * 1024:
            self.ids.global_speed.text = f"{speed_to_show / 1024:.1f} KB/s"
        else:
            self.ids.global_speed.text = f"{speed_to_show / (1024 * 1024):.2f} MB/s"

        # --- Suavizado de ETA Global ---
        if speed_to_show > 1024 and total_remaining_bytes > 0:
            eta_instant = total_remaining_bytes / speed_to_show
            
            alpha_eta = 0.08 # Un poco más lento para el ETA global
            if self._global_eta_seconds_smooth is None:
                self._global_eta_seconds_smooth = eta_instant
            else:
                self._global_eta_seconds_smooth = (
                    (self._global_eta_seconds_smooth * (1 - alpha_eta)) + (eta_instant * alpha_eta)
                )
            
            eta_s = self._global_eta_seconds_smooth
            
            if eta_s < 60:
                self.ids.global_eta.text = f"{int(eta_s)}s"
            elif eta_s < 3600:
                m, s = divmod(int(eta_s), 60)
                self.ids.global_eta.text = f"{m}m {s}s"
            else:
                h, rem = divmod(int(eta_s), 3600)
                m, _ = divmod(rem, 60)
                self.ids.global_eta.text = f"{h}h {m}m"
        else:
            if speed_to_show <= 50: # Umbral mínimo para considerar "parado"
                self.ids.global_speed.text = "0.00 MB/s"
                self._global_eta_seconds_smooth = None
            self.ids.global_eta.text = "--"

    def complete_download(self, message):
        message = message.decode("utf-8")
        filename = message
        try:
            download_item = next((c for c in self.ids.download_list.children if c.filename == filename), None)
            if download_item:
                download_item._current_speed_bps = 0
                self.recalculate_global_stats()
                def update_ui(*args):
                    download_item.ids['label_text_2'].text = 'Descarga Finalizada'
                    if 'label_percent' in download_item.ids:
                        download_item.ids['label_percent'].text = '100%'
                    if 'label_eta' in download_item.ids:
                        download_item.ids['label_eta'].text = 'Completa'
                    if 'label_size' in download_item.ids:
                        total_bytes = getattr(download_item, '_total_bytes', 0)
                        if total_bytes > 0:
                            formatted_total = self.sizeof_fmt(total_bytes)
                            download_item.ids['label_size'].text = f"{formatted_total} / {formatted_total}"
                    download_item.ids["progress_circle"].set_progress(100)
                    if 'pause_btn' in download_item.ids:
                        p_btn = download_item.ids['pause_btn']
                        if p_btn.parent: p_btn.parent.remove_widget(p_btn)
                    if 'retry_btn' in download_item.ids:
                        r_btn = download_item.ids['retry_btn']
                        if r_btn.parent: r_btn.parent.remove_widget(r_btn)
                Clock.schedule_once(update_ui) 
        except Exception as e:
            print(f"Error completing download: {e}")

    def save_downloads(self):
        """Guarda la lista de descargas en un archivo JSON"""
        try:
            with open(SAVE_PATH, "w") as f:
                json.dump(self.active_downloads, f)
        except Exception as e:
            print(f"❌ Error guardando descargas: {e}")

    def load_downloads(self):
        """Carga la ruta guardada en download_path.json y la muestra en status_label_2."""
        json_file = os.path.join(os.path.dirname(SAVE_PATH), "download_path.json")
        # 1. Cargar Configuración (Ruta y Tema)
        if os.path.exists(json_file):
            try:
                with open(json_file, "r") as f:
                    data = json.load(f)

                    # Cargar Ruta
                    saved_path = data.get("selected_path", "No hay ruta guardada")
                    self.ids.status_label_2.text = f"Ruta: {saved_path}"

                    # Cargar y aplicar Tema
                    saved_theme = data.get("theme_mode", "Dark")
                    self.apply_theme_logic(saved_theme)
            except Exception as e:
                print(f"❌ Error cargando configuración: {e}")
        else:
            self.ids.status_label_2.text = os.path.join(os.path.dirname(SAVE_PATH))

        # 2. Carga la lista de descargas desde el archivo JSON y reconstruye la UI.
        try:
            status_map = {}
            if os.path.exists(status_path):
                with open(status_path, "r") as f:
                    status_map = json.load(f)

            if os.path.exists(SAVE_PATH):
                with open(SAVE_PATH, "r") as f:
                    self.active_downloads = json.load(f)
                    print("✅ Descargas cargadas desde archivo.")

                # 🔄 Restaurar los widgets en la UI
                current_filenames = [download_item.filename for download_item in self.ids.download_list.children]
                for download in self.active_downloads:
                    filename = download["filename"]
                    file_path = download["file_path"]
                    display_name = download["display_name"]
                    url_data = download.get("url_data")
                    total_size = download.get("total_size", 0)
                    if filename not in current_filenames:
                        self.add_download(filename, file_path, display_name, save_to_disk=False, url_data=url_data, total_size=total_size)
                        status_info = status_map.get(filename, {})
                        estado = status_info.get("status")
                        error_msg = status_info.get("error")
                        if estado == "completed":
                            Clock.schedule_once(
                                lambda dt, fn=filename: self.mark_as_completed(fn),
                                0.1
                            )
                        elif estado == "error":
                            Clock.schedule_once(
                                lambda dt, fn=filename, em=error_msg: self.mark_as_error(fn, em),
                                0.1
                            )
                print(f"📌 {len(self.active_downloads)} descargas restauradas en la UI.")
        except Exception as e:
            print(f"❌ Error cargando descargas: {e}")

    def mark_as_error(self, filename, error_msg=None):
        download_item = next(
            (c for c in self.ids.download_list.children
            if c.filename == filename),
            None
        )

        if not download_item:
            return
        download_item.ids['label_text_2'].text = error_msg[:40]
        if error_msg:
            download_item.ids['label_eta'].text = "Error en descarga"
        download_item.ids['label_percent'].text = "Error"
        download_item.ids['label_speed'].text = "0 KB/s"
        download_item.ids["progress_circle"].set_progress(0)

    def mark_as_completed(self, filename):
        download_item = next((c for c in self.ids.download_list.children if c.filename == filename), None)
        if download_item:
            download_item.ids['label_text_2'].text = 'Descarga Finalizada'
            if 'label_percent' in download_item.ids:
                download_item.ids['label_percent'].text = '100%'
            if 'label_eta' in download_item.ids:
                download_item.ids['label_eta'].text = 'Completa'
            if 'label_speed' in download_item.ids:
                download_item.ids['label_speed'].text = '0 KB/s'
            if 'label_size' in download_item.ids:
                total_bytes = getattr(download_item, '_total_bytes', 0)
                if total_bytes > 0:
                    formatted_total = self.sizeof_fmt(total_bytes)
                    download_item.ids['label_size'].text = f"{formatted_total} / {formatted_total}"
            download_item.ids["progress_circle"].set_progress(100)
            if 'pause_btn' in download_item.ids:
                p_btn = download_item.ids['pause_btn']
                if p_btn.parent: p_btn.parent.remove_widget(p_btn)
            if 'retry_btn' in download_item.ids:
                r_btn = download_item.ids['retry_btn']
                if r_btn.parent: r_btn.parent.remove_widget(r_btn)

    def start_service(self):
        if platform == "android":
            try:
                service = autoclass('org.kivis.downfree.ServiceArgo')
                self.mActivity = autoclass('org.kivy.android.PythonActivity').mActivity
                argument = ''
                service.start(self.mActivity, argument)
                self.service = service
            except Exception as e:
                print(f"❌ Error iniciando el servicio: {e}")
        elif platform in ('linux', 'linux2', 'macos', 'win'):
            from runpy import run_path
            from threading import Thread
            self.service = Thread(
                target=run_path,
                args=['myservice.py'],
                kwargs={'run_name': '__main__'},
                daemon=True
            )
            self.service.start()
        else:
            raise NotImplementedError(
                "service start not implemented on this platform"
            )
        print("Iniciando Servicio")

    def stop_service(self):
        if self.service:
            if platform == "android":
                self.service.stop(self.mActivity)
            elif platform in ('linux', 'linux2', 'macos', 'win'):
                self.service.stop()
            else:
                raise NotImplementedError(
                    "service start not implemented on this platform"
                )
            self.service = None

    #### INICIO #####
    def on_pause(self):
        print("⚠️ Aplicación en pausa. Evitando cierre...")
        if platform == "android":
            if self.wake_lock and not self.wake_lock.isHeld():
                self.wake_lock.acquire()
            elif not self.wake_lock:
                self.wake_lock = acquire_wake_lock()
        return True 

    def on_resume(self):
        print("🔄 Aplicación reanudada. Restaurando Wake Lock...")
        if platform == "android":
            if self.wake_lock and not self.wake_lock.isHeld():
                self.wake_lock.acquire()
            elif not self.wake_lock:
                self.wake_lock = acquire_wake_lock()
        Clock.schedule_once(lambda dt: self.restore_ui(), 0.5)

    def restore_ui(self):
        try:
            if os.path.exists(SAVE_PATH):
                with open(SAVE_PATH, "r") as f:
                    active_json_data = json.load(f)
                valid_filenames = [d["filename"] for d in active_json_data]
            else:
                valid_filenames = []
            # 2. Limpieza: Eliminar de la UI lo que ya no existe en el JSON
            for child in list(self.ids.download_list.children):
                if hasattr(child, 'filename') and child.filename not in valid_filenames:
                    self.ids.download_list.remove_widget(child)
            # 3. Cargar/Sincronizar lo nuevo
            self.load_downloads()
        except Exception as e:
            print("Error restaurando UI:", e)
            
    def on_stop(self):
        print("⚠️ Aplicación cerrándose...")
        if self.service:
            try:
                self.stop_service()
                print("✅ Servicio detenido correctamente.")
            except Exception as e:
                print(f"❌ Error al detener el servicio: {e}")
        if platform == "android":
            if self.wake_lock and self.wake_lock.isHeld():
                print("🔓 Liberando Wake Lock...")
                release_wake_lock(self.wake_lock)
                self.wake_lock = None
        self.stop_event.set()

def mostrar_error(mensaje):
    mensaje = mensaje.decode("utf-8")
    Clock.schedule_once(lambda dt: mostrar_error_popup(mensaje))

def mostrar_error_popup(mensaje):
    label = Label(text=mensaje, size_hint=(None, None), size=(250, 100), text_size=(250, None), halign='center', valign='middle')
    label.bind(size=label.setter('text_size'))
    popup = Popup(title='', content=label, size_hint=(None, None), size=(250, 100))
    popup.open()

def tst_view(mensaje):
    mensaje = mensaje.decode("utf-8")
    tst(str(mensaje))

if __name__ == "__main__":
    try:
        Downloader().run()
    except Exception as ex:
        print(f"⚠️ Error crítico en la aplicación: {ex}")