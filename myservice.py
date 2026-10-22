'p4a example service using oscpy to communicate with main application.'
from kivy.clock import Clock
from kivy.utils import platform

from time import sleep

from oscpy.server import OSCThreadServer
from oscpy.client import OSCClient

import requests, base64, json
import queue
import threading
import ast
import os
import time
import urllib3
import re
from bs4 import BeautifulSoup
from requests.exceptions import Timeout

active_downloads = {}
pause_events = {}

max_concurrent_downloads = 1
active_download_count = 0
count_lock = threading.Lock()
queue_manager_thread = None
status_lock = threading.Lock()

try:
    def log_message(msg):
        """Envía mensajes de log a la app principal o a la consola."""
        CLIENT.send_message(b'/service_log', [msg.encode()])

    if platform == "android":
        from jnius import autoclass, cast

        PendingIntent = autoclass("android.app.PendingIntent")
        PythonActivity = autoclass("org.kivy.android.PythonActivity")
        PythonService = autoclass("org.kivy.android.PythonService")
        Service = PythonService.mService
        Intent = autoclass("android.content.Intent")
        String = autoclass("java.lang.String")
        Bundle = autoclass("android.os.Bundle")
        
        BuildVersion = autoclass("android.os.Build$VERSION")

        Context = autoclass("android.content.Context")
        NotificationCompat = autoclass("androidx.core.app.NotificationCompat")
        NotificationCompatBuilder = autoclass('androidx.core.app.NotificationCompat$Builder')
        NotificationManager = autoclass("android.app.NotificationManager")
        NotificationChannel = autoclass("android.app.NotificationChannel")
        PendingIntent = autoclass('android.app.PendingIntent')

        CHANNEL_ID = "download_service"
        NOTIFICATION_ID = 101
        FINAL_NOTIFICATION_ID = 102

        def get_notification_manager():
            """Obtiene el NotificationManager de Android."""
            return Service.getSystemService(Context.NOTIFICATION_SERVICE)
        
        def create_intent():
            """Crea el Intent para abrir la aplicación cuando se toca la notificación."""
            context = PythonService.mService
            intent = Intent(context, PythonActivity)
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return context, intent
        
        def get_pending_intent():
            """Crea un PendingIntent para la notificación."""
            context, intent = create_intent()
            return PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE if BuildVersion.SDK_INT >= 31 else PendingIntent.FLAG_UPDATE_CURRENT
            )
        
        def create_notification_channel():
            """Crea el canal de notificación si el SDK es 26+."""
            if BuildVersion.SDK_INT >= 26:
                try:
                    manager = get_notification_manager()
                    channel_name = "Servicio de Descarga"
                    importance = NotificationManager.IMPORTANCE_HIGH
                    app_channel = NotificationChannel(CHANNEL_ID, channel_name, importance)
                    manager.createNotificationChannel(app_channel)
                except Exception as e:
                    log_message(f"Error al crear el canal de notificación: {e}")

        def build_notification(title, message, progress=None, auto_cancel=False, alert_once=True):
            """Crea un NotificationCompatBuilder con configuración común."""
            if BuildVersion.SDK_INT >= 26:
                create_notification_channel()
                notifi = NotificationCompatBuilder(Service, CHANNEL_ID)
            else:
                notifi = NotificationCompatBuilder(Service) 

            pending_intent = get_pending_intent()

            notifi.setContentTitle(title)
            notifi.setContentText(message)
            notifi.setSmallIcon(Service.getApplicationInfo().icon)
            notifi.setPriority(NotificationCompat.PRIORITY_HIGH)
            notifi.setAutoCancel(auto_cancel)
            notifi.setContentIntent(pending_intent)
            notifi.setOnlyAlertOnce(alert_once)
            
            if progress is not None:
                notifi.setProgress(100, progress, False)

            return notifi

        def create_notification():
            """Crea una notificación persistente para mantener el servicio en segundo plano."""
            manager = get_notification_manager()
            notifi = build_notification(
                "Gestor de Descargas", "El servicio de descargas está en ejecución", auto_cancel=False
            )
            manager.notify(NOTIFICATION_ID, notifi.build())
            Service.startForeground(NOTIFICATION_ID, notifi.build())

        def update_notification(progress, filename, notif_id):
            """Actualiza la notificación con el progreso de la descarga."""
            manager = get_notification_manager()
            notifi = build_notification(f"Descargando: {filename}", f"{progress}% completado", progress)
            manager.notify(notif_id, notifi.build())

        def complete_notification(filename, notif_id):
            """Muestra una notificación cuando la descarga ha finalizado."""
            manager = get_notification_manager()
            notifi = build_notification(
                "Descarga Completa", f"{filename} descargado con éxito", auto_cancel=True, alert_once=False
            )
            notifi.setProgress(0, 0, False)  # Remover barra de progreso
            manager.notify(notif_id, notifi.build())
            # Cancelar la notificación después de 10 segundos
            Clock.schedule_once(lambda dt: manager.cancel(notif_id), 10)

        def error_notification(filename, error_message, notif_id):
            """Muestra una notificación cuando ocurre un error en la descarga."""
            manager = get_notification_manager()
            # Truncar nombre de archivo si es muy largo
            file_disp = filename
            if len(file_disp) > 25:
                file_disp = filename[:20] + "." + filename[-5:]
            notifi = build_notification(
                f"Error: {file_disp}", error_message, auto_cancel=True, alert_once=False
            )
            notifi.setProgress(0, 0, False)  # Remover barra de progreso
            manager.notify(notif_id, notifi.build())

    if platform == 'android':
        from jnius import autoclass
        from android.storage import primary_external_storage_path

        PythonService = autoclass('org.kivy.android.PythonService')

        Context = autoclass('android.content.Context')
        Environment = autoclass('android.os.Environment')

        def get_app_specific_storage_path():
            try:
                service = autoclass('org.kivy.android.PythonService').mService
                context = service.getApplicationContext()
                storage_dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

                if storage_dir is not None:
                    return storage_dir.getAbsolutePath()

                # Fallback si devuelve None
                return context.getFilesDir().getAbsolutePath()

            except Exception as e:
                print("Error obteniendo directorio:", e)
                # Último fallback interno
                service = autoclass('org.kivy.android.PythonService').mService
                return service.getFilesDir().getAbsolutePath()

    else:
        download_dir = 'Downloads_C'
        if not os.path.exists(download_dir):
            os.makedirs(download_dir)

    CLIENT = OSCClient('127.0.0.1', 3002)

    connection_lost_event = threading.Event() # Evento para indicar pérdida de conexión
    download_queue = queue.Queue()
    download_thread = None

    headers = {"User-Agent":"Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"}

    def init_paths():
        global SAVE_PATH

        if platform == "android":
            try:
                service = autoclass('org.kivy.android.PythonService').mService
                context = service.getApplicationContext()
                storage_dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

                if storage_dir is not None:
                    base_path = storage_dir.getAbsolutePath()
                else:
                    base_path = context.getFilesDir().getAbsolutePath()

            except Exception as e:
                log_message(f"Error obteniendo storage: {e}")
                base_path = service.getFilesDir().getAbsolutePath()

            SAVE_PATH = os.path.join(base_path, "downloads.json")
        else:
            SAVE_PATH = os.path.join("Downloads_C", "downloads.json")


    selected_path = None

    def get_selected_path(*args):
        """Lee la ruta guardada en download_path.json"""
        global selected_path
        selected_path_file = os.path.join(os.path.dirname(SAVE_PATH), "download_path.json")
        if os.path.exists(selected_path_file):
            try:
                with open(selected_path_file, "r") as f:
                    selected_path_data = json.load(f)
                    selected_path = selected_path_data.get("selected_path", None)
                    log_message(f"📂 Ruta de descarga actualizada: {selected_path}")
            except Exception as e:
                log_message(f"❌ Error leyendo download_path.json: {e}")
                selected_path = None
        if not selected_path:
            selected_path = get_app_specific_storage_path() if platform == "android" else "Downloads_C"

    def make_session(dl):
        session = requests.Session()
        username = dl['u']
        password = dl['p']

        if dl['m'] in ['m', 'todus']:
            return session
        
        try:
            if dl['m'] in ['cocmed', 'evea', 'md', 'ts']:
                #base64_url = "aHR0cHM6Ly9kb3duZnJlZS1hcGlkYXRhLm9ucmVuZGVyLmNvbS9zZXNzaW9u"
                decoded_url = "https://downfree-hiyabo.onrender.com/session"
                v = str(dl["id"])
                resp = requests.post(decoded_url, json={"id":v}, headers={'Content-Type':'application/json'}, timeout=15)
                data = json.loads(resp.text)
                session.cookies.update(data)
                return session
            
            if dl['m'] == 'moodle':
                url = dl['c']+'login/index.php'
            elif dl['m'] == 'pv_ts':
                url = dl['c'].split('ndex.php?P')[0]+"index.php?P=UserLogin"
            elif dl['m'] == 'rev2':
                url = dl['c'].split('author')[0]+"login/signIn"
            else:
                original_url = dl['urls'][0]
                base_url = original_url.partition('/$$$call$$$')[0]
                url = f"{base_url}/login/signIn"

            resp = session.get(url, headers=headers, allow_redirects=True, verify=False, timeout=15)
            resp.raise_for_status()
            soup = BeautifulSoup(resp.text, "html.parser")

            if dl['m'] == 'moodle':
                try:
                    token = soup.find("input", attrs={"name": "logintoken"})["value"]
                    payload = {"anchor": "", "logintoken": token, "username": username, "password": password, "rememberusername": 1}
                except:
                    payload = {"anchor": "", "username": username, "password": password, "rememberusername": 1}
            elif dl['m'] == 'pv_ts':
                payload = {"F_UserName":username,"F_Password":password}
            elif dl['m'] == 'rev2':
                payload = {"source":"", "username":username, "password":password, "remember":"1"}
            else:
                csrf_tag = soup.find('input', {'name': 'csrfToken'})
                if csrf_tag:
                    payload = {'csrfToken': csrf_tag['value'], 'source': '', 'username': username, 'password': password, 'remember': '1'}
                else:
                    payload = {'source': '', 'username': username, 'password': password, 'remember': '1'}
                    
            resp = session.post(url, headers=headers, data=payload, verify=False, timeout=20)
            if resp.url!=url:
                return session
            return None
        
        except Exception as e:
            status_label(f"Error de red creando sesión")
            return None

    def cancel_download(data):
        """Cancela la descarga si aún está en progreso."""
        try:
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            filename = data.strip()

            if filename in active_downloads:
                active_downloads[filename].set()  # Marcar la descarga como cancelada
                if filename in pause_events:
                    pause_events[filename].set() # Asegurar que no quede bloqueada en pausa
                if platform == "android":
                    tst_view(f"⛔ Descarga eliminada: {filename}")
                print(f"Descarga de {filename} eliminada correctamente.")
            else:
                print(f"El archivo {filename} no está en la lista de activos.")
        except Exception as e:
            print(f"Error al cancelar descarga: {e}")
            if platform == "android":
                tst_view(f"Error al cancelar descarga")

    def set_max_downloads(data):
        global max_concurrent_downloads
        max_concurrent_downloads = int(data.decode("utf-8"))

    def start_download(data):
        try:
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            if isinstance(data, str):
                data = ast.literal_eval(data)

            filename = data.get("fn", "archivo_desconocido")
            
            if filename in active_downloads:
                log_message(f"⚠️ {filename} ya está en curso o en espera.")
                return
            
            cancel_event = threading.Event()
            active_downloads[filename] = cancel_event
            
            # Configurar evento de pausa activo (permitiendo tráfico)
            pause_events[filename] = threading.Event()
            pause_events[filename].set()

            download_queue.put(data)
            
            global queue_manager_thread
            if queue_manager_thread is None or not queue_manager_thread.is_alive():
                queue_manager_thread = threading.Thread(target=process_queue, daemon=True)
                queue_manager_thread.start()
        except Exception as e:
            pass

    def process_queue():
        global active_download_count
        while True:
            with count_lock:
                can_start = active_download_count < max_concurrent_downloads
            
            if can_start and not download_queue.empty():
                url = download_queue.get()
                filename = url.get("fn", "archivo_desconocido")
                
                cancel_event = active_downloads.get(filename)

                if not cancel_event or cancel_event.is_set():
                    print(f"Omitiendo {filename} porque fue cancelado en cola.")
                    if filename in active_downloads: del active_downloads[filename]
                    continue 

                with count_lock:
                    active_download_count += 1
                    
                t = threading.Thread(target=download_wrapper, args=(url, cancel_event))
                t.start()
            else:
                time.sleep(1)

    def download_wrapper(url, cancel_event):
        global active_download_count
        filename = url.get("fn", "archivo_desconocido")
        try:
            _download_file(url, cancel_event)
        except Exception as e:
            print(f"Error en hilo de descarga {filename}: {e}")
        finally:
            with count_lock:
                active_download_count -= 1
                if filename in active_downloads:
                    del active_downloads[filename]
                if filename in pause_events:
                    del pause_events[filename]
            print(f"Slot liberado por: {filename}. Activos: {active_download_count}")


    def request_todus_url(url, headers=None, range_header=None):
        """Realiza una petición HTTPS forzando SNI y Host a s3.todus.cu conectando a la IP 152.207.206.86."""
        from urllib.parse import urlparse
        parsed = urlparse(url)
        path = parsed.path
        if parsed.query:
            path += "?" + parsed.query
        
        req_headers = dict(headers) if headers else {}
        req_headers["Host"] = "s3.todus.cu"
        if range_header:
            req_headers.update(range_header)
            
        pool = urllib3.HTTPSConnectionPool(
            host="152.207.206.86",
            server_hostname="s3.todus.cu",
            assert_hostname="s3.todus.cu",
            cert_reqs='CERT_NONE'
        )
        
        resp = pool.request(
            "GET",
            path,
            headers=req_headers,
            preload_content=False,
            timeout=30.0
        )
        return resp
    
    def _download_file(dl, cancel_event):
        """Lógica de descarga basada en tu código original."""
        try:
            filename = dl['fn']
            file_notif_id = abs(hash(filename)) % 10000 + 1000 

            if cancel_event.is_set():
                return
            total_size = dl['fs']
            is_todus = (dl.get('m') == 'todus')

            if dl["m"] in ["cocmed","moodle","m","ts","md","rev2","pv_ts","evea","r","todus"]:
                unshort_result = unshort(dl["urls"])
                if unshort_result is None:
                    unshort_result = False # Marcamos fallo
                else:
                    dl['urls'] = ast.literal_eval(unshort_result)

            session = make_session(dl)
            if unshort_result is False or not session:
                label_text("No se pudo iniciar sesión.", filename)
                save_download_status(
                    filename,
                    "error",
                    error_message= "No se pudo iniciar sesión."
                )
                if platform == "android":
                    error_notification(filename, "No se pudo iniciar sesión.", file_notif_id)
                return
            
            if selected_path:
                base_path = os.path.join(selected_path, "Down_Free")
            else:
                base_path = os.path.join(get_app_specific_storage_path() if platform == "android" else "Downloads_C", "Down_Free")

            temp_dir = os.path.join(base_path, "Parts")
            final_dir = os.path.join(base_path, "Downloads")

            os.makedirs(temp_dir, exist_ok=True)
            os.makedirs(final_dir, exist_ok=True)

            # ==================== LÓGICA EXCLUSIVA PARA TODUS ====================
            if is_todus:
                download_path = os.path.join(temp_dir, filename)
                final_path = os.path.join(final_dir, filename)
                file_url = dl['urls'][0] if isinstance(dl['urls'], list) else dl['urls']
                
                if os.path.exists(final_path) and total_size > 0 and os.path.getsize(final_path) == total_size:
                    status_label(f"✅ Archivo ya descargado")
                    CLIENT.send_message(b"/complete_download", [f"{filename}".encode()])
                    save_download_status(filename, "completed")
                    return

                retries = 0
                while retries < 5:
                    if cancel_event.is_set():
                        return
                    
                    downloaded_bytes = 0
                    if os.path.exists(download_path):
                        downloaded_bytes = os.path.getsize(download_path)
                    
                    if total_size > 0 and downloaded_bytes >= total_size:
                        break

                    range_hdr = {}
                    if downloaded_bytes > 0:
                        range_hdr['Range'] = f'bytes={downloaded_bytes}-'
                        
                    try:
                        resp = request_todus_url(file_url, headers=headers, range_header=range_hdr)
                        
                        # Manejo de códigos de respuesta en urllib3 (status en lugar de status_code)
                        if resp.status == 416:
                            break

                        mode = "ab" if resp.status == 206 and downloaded_bytes > 0 else "wb"
                        if mode == "wb":
                            downloaded_bytes = 0

                        with open(download_path, mode) as f:
                            status_label("Descargando Todus..." if downloaded_bytes == 0 else "Reanudando descarga Todus...")
                            last_update_time = time.time()
                            
                            for chunk in resp.stream(amt=8192):
                                if cancel_event.is_set():
                                    resp.release_conn()
                                    return
                                if filename in pause_events:
                                    pause_events[filename].wait()
                                
                                if chunk:
                                    f.write(chunk)
                                    downloaded_bytes += len(chunk)

                                current_time = time.time()
                                if current_time - last_update_time >= 0.5:
                                    CLIENT.send_message(b"/download_status", [json.dumps({
                                        "filename": filename,
                                        "downloaded": downloaded_bytes,
                                        "total_length": total_size
                                    }).encode()])
                                    progress = int((downloaded_bytes / total_size) * 100) if total_size > 0 else 0
                                    if platform == "android":
                                        filet = filename[:20] + "." + filename[-5:] if len(filename) > 25 else filename
                                        update_notification(progress, filet, file_notif_id)
                                    last_update_time = current_time

                        resp.release_conn()

                        if total_size > 0 and downloaded_bytes < total_size:
                            raise Exception("Descarga incompleta, la conexión se cortó antes de finalizar.")
                        break

                    except Exception as e:
                        if cancel_event.is_set():
                            return
                        retries += 1
                        print(f"⚠️ [Todus] Error en descarga (intento {retries}/5): {e}")
                        if not _retry_connection():
                            time.sleep(5)
                        if retries >= 5:
                            label_text("Error: Tras 5 intentos fallidos en Todus", filename)
                            save_download_status(filename, "error", error_message="Error: Tras 5 intentos en la descarga de Todus")
                            if platform == "android":
                                file_disp = filename[:20] + "." + filename[-5:] if len(filename) > 25 else filename
                                error_notification(file_disp, "Tras 5 intentos fallidos", file_notif_id)
                            return

                if os.path.exists(final_path):
                    os.remove(final_path)
                os.rename(download_path, final_path)

                status_label(f"✅ Archivo guardado en {str(final_dir)}")
                CLIENT.send_message(b"/complete_download", [f"{filename}".encode()])
                if platform == "android":
                    filet = filename[:20] + "." + filename[-5:] if len(filename) > 25 else filename
                    complete_notification(filet, file_notif_id)

                save_download_status(filename, "completed")
                return

            # ==================== LÓGICA ORIGINAL PARA LOS DEMÁS MÉTODOS (CHUNKS) ====================
            else:
                total_parts = dl["t"] * 1024 * 1024
                total_url = len(dl["urls"])
                chunk_por = 0
                filet = dl['fn']
                if len(filet) > 25:
                    filet = filename[:20] + "." + filename[-5:]

                download_path = os.path.join(temp_dir, filename)
                
                if os.path.exists(download_path):
                    os.unlink(download_path)

                part_files = []
                for i, chunkur in enumerate(dl['urls']):
                    part_path = f"{download_path}.part{i}"
                    part_files.append(part_path)
                    if os.path.exists(part_path):
                        chunk_por += os.path.getsize(part_path)
                        if os.path.getsize(part_path) >= total_parts:
                            log_message(f"Parte {i} ya descargada, omitiendo.")
                            continue
                    if cancel_event.is_set():
                        return
                    if dl['m'] in ['m', 'ts', 'md', 'rev2', 'pv_ts',"mined","r"]:
                        chunkurl = chunkur
                    elif dl["m"] == "cocmed":
                        chunkurl = chunkur + "/.file"
                        print(chunkurl)
                    elif dl['m'] == 'moodle' or dl['m'] == 'evea':
                        chunkurl = chunkur
                    else:
                        chunkurl = dl['c'].split('^')[0] + chunkur + dl['c'].split('^')[1]
                    retries = 0
                    while retries < 5:
                        try:
                            if "aula.uclv.edu.cu" in chunkurl:
                                if not _is_session_active(session, chunkurl, filename):
                                    log_message("Sesión inactiva, regenerando sesión...")
                                    session = make_session(dl)
                                    
                            resp = session.get(chunkurl, headers=headers, stream=True, verify=False, timeout=(10, 15))
                            _validar_respuesta_descarga(resp, chunkurl)
                            resp.raise_for_status()

                            with open(part_path, "wb") as part_file:
                                status_label("Descargando...")
                                downloaded = 0
                                last_update_time = time.time() 
                                for chunk in resp.iter_content(chunk_size=8192):
                                    if cancel_event.is_set():
                                        return
                                    if filename in pause_events:
                                        pause_events[filename].wait() 
                                    
                                    part_file.write(chunk)
                                    downloaded += len(chunk)

                                    chunk_por = sum(
                                        os.path.getsize(f"{download_path}.part{j}") for j in range(i) if os.path.exists(part_files[j])
                                    ) + downloaded

                                    current_time = time.time()
                                    if current_time - last_update_time >= 0.5:
                                        CLIENT.send_message(b"/download_status", [json.dumps({
                                                    "filename": filename,
                                                    "downloaded": chunk_por,
                                                    "total_length": total_size
                                            }).encode()])
                                        progress = int((chunk_por / total_size) * 100)
                                        if platform == "android":
                                            update_notification(progress, filet, file_notif_id)
                                        last_update_time = current_time
                                        
                            expected_size = total_parts if (i < total_url - 1) else total_size % total_parts
                            if os.path.getsize(part_path) < expected_size:
                                log_message(f"Error: La parte {i} se descargó con tamaño 0 bytes.")
                                os.remove(part_path)  # Eliminar archivo vacío
                                raise Exception("Archivo incompleto o corrupto tras descarga")
                            break

                        except FileNotFoundError as e:
                            label_text(str(e), filename)
                            save_download_status(
                                filename,
                                "error",
                                error_message=str(e)
                            )
                            if platform == "android":
                                error_notification(filename, str(e), file_notif_id)
                            return
                        
                        except (requests.exceptions.RequestException, Exception) as e:
                            if cancel_event.is_set():
                                return
                            retries += 1
                            if _retry_connection():
                                connection_lost_event.clear()
                                new_sess = make_session(dl)
                                if new_sess:
                                    session = new_sess
                            else:
                                label_text("Conexión perdida. Reintentando...", filename)
                                time.sleep(5)
                                if cancel_event.is_set():
                                    return
                            if retries >= 5:
                                label_text("Error: Tras 5 intentos en la descarga", filename)
                                save_download_status(
                                    filename,
                                    "error",
                                    error_message= "Error: Tras 5 intentos en la descarga"
                                )
                                if platform == "android":
                                    error_notification(filename, "Tras 5 intentos fallidos", file_notif_id)
                                log_message(f"Cancelando {filename}: Moodle o el servidor origen rechazó la conexión tras 5 intentos.")
                                return # Matamos el hilo porque no tiene solución mágica
                                
            if cancel_event.is_set():
                return
            
            downloaded_parts = [os.path.exists(part) and os.path.getsize(part) > 0 for part in part_files ]
            if all(downloaded_parts) and len(downloaded_parts) == total_url:
                CLIENT.send_message(b"/download_status", [json.dumps({
                    "filename": filename,
                    "downloaded": total_size,
                    "total_length": total_size
                }).encode()])
                time.sleep(0.2)  # Dar un pequeño margen para procesar el estado en la UI
                label_text("Uniendo partes...", filename)
                _merge_parts(download_path, len(part_files))
            else:
                label_text("Faltaron partes del archivo por descargar. Verifica la conexión y reintenta.", filename)
                save_download_status(
                    filename,
                    "error",
                    error_message="Faltaron partes del archivo por descargar. Verifica la conexión y reintenta."
                )    
                if platform == "android":
                    error_notification(filename, "Faltaron partes del archivo por descargar.", file_notif_id)    

            final_path = os.path.join(final_dir, filename)
            if os.path.exists(final_path):
                os.remove(final_path)
            os.rename(download_path, final_path)
            for part_file in part_files:
                if os.path.exists(part_file):
                    os.remove(part_file)

            v = ["cocmed", "m", "moodle", "evea", "ts", "pv_ts"]
            if not dl["m"] in v:
                chunk_size = 1024 * 1024 
                target_bytes = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc```\x00\x00\x00\x04\x00\x01\xf6\x178U\x00\x00\x00\x00IEND\xaeB`\x82"
                replacement_bytes = b""
                def replace_bytes(chunk, target, replacement):
                    return chunk.replace(target, replacement)
                with open(final_path, "rb") as original_file, open(final_path + ".tmp", "wb") as new_file:
                    while True:
                        chunk = original_file.read(chunk_size)
                        if not chunk:
                            break
                        modified_chunk = replace_bytes(chunk, target_bytes, replacement_bytes)
                        new_file.write(modified_chunk)
                os.replace(final_path + ".tmp", final_path)

            status_label(f"Archivo guardado en {str(final_dir)}")

            CLIENT.send_message(b"/complete_download", [f"{filename}".encode()])
            if platform == "android":
                #update_notification(100, filet, file_notif_id)
                complete_notification(filet, file_notif_id)

            save_download_status(filename, "completed")
        except Exception as e:
            label_text(f"No se pudo conectar al servidor.", filename)
            save_download_status(
                filename,
                "error",
                error_message= "No se pudo conectar al servidor."
            )
            if platform == "android":
                error_notification(filename, "No se pudo conectar al servidor.", file_notif_id)
            #log_message(f"Error: {str(e)}")
        finally:
            try:
                session.close()
            except Exception:
                pass

    def _validar_respuesta_descarga(resp, chunkurl):
        # Enlaces borrados o vencidos
        if resp.status_code in (404, 410):
            raise FileNotFoundError("El enlace ya fue eliminado.")

        # Redirección rara = enlace ya no apunta al archivo
        if resp.status_code in (301, 302, 303, 307, 308):
            raise FileNotFoundError("El enlace redirige y ya no apunta al archivo.")

        # Si responde HTML, normalmente es login/error y no un binario real
        ctype = resp.headers.get("Content-Type", "").lower()
        if "text/html" in ctype:
            raise FileNotFoundError("El enlace devolvió HTML en lugar del archivo.")
        
    def check_connection():
        """Verifica el estado de la conexión."""
        try:
            response = requests.get('https://www.portal.nauta.cu/login', timeout=5)  # Chequea conexión
            if response.status_code == 200:
                if connection_lost_event.is_set():
                    connection_lost_event.clear()
                    status_label("Conexión restaurada")
                return True
        except (requests.ConnectionError, Timeout):
            if not connection_lost_event.is_set():
                connection_lost_event.set()
                status_label("Conexión perdida. Reintentando...")
        return False
        
    def _retry_connection():
        """Intenta reconectar verificando la conexión."""
        for _ in range(5):  # Máximo 5 intentos rápidos
            if check_connection():
                return True
            time.sleep(5)
        return False

    def obtener_contrasena():
        # Ofuscar la contraseña
        password = "RzdhIXpQcSUza1ZuXnRZdQ=="
        # Decodificar la contraseña
        return base64.b64decode(password).decode('utf-8')

    def unshort(enlace_corto):
        url = f"https://downfree-hiyabo.onrender.com/unshort/{enlace_corto}"
        try:
            resp = requests.get(url, timeout=5)
            print(f"Estado: {resp.status_code}")
            if resp.status_code == 200:
                return resp.json()["enlace_largo"]
        except requests.exceptions.RequestException as e:
            print(f"Error al conectar con la API")
        return None
    
    def _is_session_active(session, test_url, filename):
        if not isinstance(session, requests.Session):
            label_text("El objeto sesión no es válido.", filename)
            return False
        try:
            # Realizar una solicitud HEAD para verificar la conexión
            resp = session.head(test_url, timeout=30, verify=False)
            # Si el estado es 200, 204, o similar, la sesión está activa
            return resp.status_code in {200, 204}  # Incluye códigos comunes de éxito
        except requests.exceptions.RequestException as ex:
            #status_label(f"❌ Error al verificar sesión: {ex}")
            label_text(f"Error al verificar sesión. Puede que la nube este caida en este momento. Intente mas tarde", filename)
            return False

    def _merge_parts(output_path, num_parts):
        """Une todas las partes descargadas en un único archivo."""
        try:
            with open(output_path, "wb") as final_file:
                for i in range(num_parts):
                    part_path = f"{output_path}.part{i}"
                    if not os.path.exists(part_path):
                        mostrar_error(f"Parte faltante: {part_path}. No se puede completar la unión.")
                        log_message(f"Parte faltante: {part_path}. No se puede completar la unión.")
                        return
                    with open(part_path, "rb") as part_file:
                        while chunk := part_file.read(8192):
                            final_file.write(chunk)
                    os.remove(part_path)  # Eliminar la parte una vez unida
            log_message(f"Descarga completada y unida: {output_path}")
        except Exception as e:
            mostrar_error(f"Error al unir las partes: {e}")

    #WEB
    import base64
    import json
    import logging
    from flask import Flask, Response, abort, request, redirect, url_for

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[logging.StreamHandler()]  # 🔴 asegura salida solo a consola/logcat
    )
    logger = logging.getLogger(__name__)

    sessions_cache = {}
    sessions_lock = threading.Lock() 

    DEVELOPER_CONTACT = "https://t.me/Hiyabo" 
    CHUNK_SLEEP_TIME = float(os.getenv('CHUNK_SLEEP_TIME', '0.1'))
    VIDEO_EXTENSIONS = ['.mp4', '.webm', '.ogg', '.mkv']

    app = Flask(__name__)

    def convert(raw: str) -> str:
        if raw.strip().startswith("{") and "'" in raw:
            print("[🧼 sanitize_json_input] Detectadas comillas simples. Normalizando...")
            raw = re.sub(r"(?<!\\)'", '"', raw)
        return raw

    def parse_link(payload: dict) -> str:
        json_str = json.dumps(payload, separators=(',', ':'))
        b64_bytes = base64.urlsafe_b64encode(json_str.encode('utf-8'))
        print(f"[🔍 parse_link] JSON serializado: {json_str}")
        print(f"[🔍 parse_link] Token base64: {b64_bytes.decode('utf-8')}")
        return b64_bytes.decode('utf-8')

    def generate_link(payload: dict, endpoint: str = "download", host: str = "127.0.0.1", port: int = 5000) -> str:
        token = parse_link(payload)
        link = f"http://{host}:{port}/{endpoint}/{token}"
        print(f"[🔗 generate_link] Enlace generado: {link}")
        return link

    def expand_urls_once(dl_obj: dict) -> dict:
        """Expande las URLs si todavía son un string."""
        method = dl_obj.get("m", "")
        urls = dl_obj.get("urls")

        if method in {"cocmed", "moodle", "evea", "md", "ts", "pv_ts"} and isinstance(urls, str):
            logger.info(f"Expandiendo URLs para método {method}")
            expanded_json_response = unshort(urls)
            if expanded_json_response:
                try:
                    urls_list = ast.literal_eval(expanded_json_response)
                    dl_obj["urls"] = urls_list
                    #logger.info(f"URLs expandidas y decodificadas: {dl_obj['urls']}")
                except (ValueError, SyntaxError) as e:
                    logger.error(f"Error al decodificar URLs: {e}")
                    dl_obj["urls"] = [urls]
            else:
                logger.warning("unshort no devolvió una respuesta válida.")
                dl_obj["urls"] = [urls]

        # normalizar a lista siempre
        if not isinstance(dl_obj.get("urls"), list):
            dl_obj["urls"] = [dl_obj["urls"]]

        return dl_obj

    def get_cached_session(dl_data: dict):
        """Obtiene una sesión cacheada por combinación de método y host."""
        host = dl_data.get("c") or "default"
        key = (dl_data.get("m"), host)

        with sessions_lock:
            session = sessions_cache.get(key)
            if not session or (host != "default" and not _is_session_active(session, host)):
                logger.info(f"Creando nueva sesión para {key}")
                session = make_session(dl_data)
                if not session:
                    raise RuntimeError("No se pudo iniciar sesión")
                sessions_cache[key] = session

        return session

    def download_generator(dl: dict, range_start=0, range_end=None):
        logger.info(f"Iniciando descarga para dl_obj: {dl.get('fn', 'N/A')}")

        dl_data = dict(dl)
        session = get_cached_session(dl_data)

        filename = dl_data.get('fn', 'download.bin')

        processed_urls = []
        for i, chunkur in enumerate(dl_data['urls']):
            m = dl_data.get('m', '')
            chunkurl = chunkur

            if m in ['m', 'ts', 'md', 'rev2', 'pv_ts',"mined",'moodle', 'evea', 'r']:
                chunkurl = chunkur
            elif m == 'cocmed':
                chunkurl = chunkur.rstrip('/') + '/.file'
            else:
                chunkurl = dl_data['c'].split('^')[0] + chunkur + dl_data['c'].split('^')[1]

            processed_urls.append(chunkurl)
            logger.info(f"Procesando chunk {i+1}/{len(dl_data['urls'])}: {chunkurl}")

        # Obtener tamaños de chunks directo y no por fetch_chunk_lengths que realentizaba todo
        total_size = dl.get('fs', 0)
        part_size = dl['t'] * 1024 * 1024  
        num_parts = len(processed_urls)
        chunk_sizes = []

        for i in range(num_parts):
            if i < num_parts - 1:
                chunk_sizes.append(part_size)
            else:
                last_size = total_size - part_size * (num_parts - 1)
                chunk_sizes.append(last_size)

        if range_end is None:
            range_end = total_size - 1
        if range_start > range_end or range_start >= total_size:
            raise ValueError("Rango inválido")

        
        cumulative_offset = 0
        for idx, (chunkurl, chunk_size) in enumerate(zip(processed_urls, chunk_sizes)):
            chunk_start = cumulative_offset
            chunk_end = cumulative_offset + chunk_size - 1

            # Aqui se verifica si el chunk es relevante o no , De no serlo pues se salta
            if chunk_end < range_start or chunk_start > range_end:
                cumulative_offset += chunk_size
                continue

            # Aqui Se calculan los Sub rangos de cada chunk
            local_start = max(0, range_start - chunk_start)
            local_end = min(chunk_size - 1, range_end - chunk_start)

            #Esgo solicita el rango
            range_req_header = {'Range': f'bytes={local_start}-{local_end}'}
            try:
                # Uso de with para cerrar respuesta automáticamente
                with session.get(chunkurl, headers={**headers, **range_req_header}, stream=True, verify=False) as resp:
                    resp.raise_for_status()
                    for chunk in resp.iter_content(chunk_size=8192):
                        if chunk:
                            yield chunk
                logger.info(f"Chunk {idx+1} enviado (rango local {local_start}-{local_end})")
            except Exception as e:
                logger.error(f"Error descargando chunk {idx+1}: {e}")
                raise RuntimeError(f"Error en chunk: {e}")

            cumulative_offset += chunk_size
            time.sleep(CHUNK_SLEEP_TIME)

    def stream_generator(dl: dict, range_start=0, range_end=None):
        logger.info(f"Iniciando streaming para dl_obj: {dl.get('fn', 'N/A')} con rango {range_start}-{range_end}")

        dl_data = dict(dl)
        session = get_cached_session(dl_data)

        filename = dl_data.get('fn', 'stream.bin')

        processed_urls = []
        for i, chunkur in enumerate(dl_data['urls']):
            m = dl_data.get('m', '')
            chunkurl = chunkur

            if m in ['m', 'ts', 'md', 'rev2', 'pv_ts',"mined",'moodle', 'evea', 'r']:
                chunkurl = chunkur
            elif m == 'cocmed':
                chunkurl = chunkur.rstrip('/') + '/.file'
            else:
                chunkurl = dl_data['c'].split('^')[0] + chunkur + dl_data['c'].split('^')[1]

            processed_urls.append(chunkurl)
            logger.info(f"Procesando chunk {i+1}/{len(dl_data['urls'])}: {chunkurl}")

        # Obtener tamaños de chunks directo y no por fetch_chunk_lengths que realentizaba todo
        total_size = dl.get('fs', 0)
        part_size = dl['t'] * 1024 * 1024  
        num_parts = len(processed_urls)
        chunk_sizes = []

        for i in range(num_parts):
            if i < num_parts - 1:
                chunk_sizes.append(part_size)
            else:
                last_size = total_size - part_size * (num_parts - 1)
                chunk_sizes.append(last_size)

        if range_end is None:
            range_end = total_size - 1
        if range_start > range_end or range_start >= total_size:
            raise ValueError("Rango inválido para streaming")

        # Mapear offsets acumulativos
        cumulative_offset = 0
        for idx, (chunkurl, chunk_size) in enumerate(zip(processed_urls, chunk_sizes)):
            chunk_start = cumulative_offset
            chunk_end = cumulative_offset + chunk_size - 1

            # Si el chunk no intersecta el range, salta
            if chunk_end < range_start or chunk_start > range_end:
                cumulative_offset += chunk_size
                continue

            # Calcular sub-range local en este chunk
            local_start = max(0, range_start - chunk_start)
            local_end = min(chunk_size - 1, range_end - chunk_start)

            # Solicitar sub-range al origen
            range_req_header = {'Range': f'bytes={local_start}-{local_end}'}
            try:
                # Uso de with para cerrar respuesta automáticamente
                with session.get(chunkurl, headers={**headers, **range_req_header}, stream=True, verify=False) as resp:
                    resp.raise_for_status()
                    for chunk in resp.iter_content(chunk_size=8192):
                        if chunk:
                            yield chunk
                logger.info(f"Chunk {idx+1} enviado en streaming (rango local {local_start}-{local_end})")
            except Exception as e:
                logger.error(f"Error en streaming chunk {idx+1}: {e}")
                raise RuntimeError(f"Error en chunk durante streaming: {e}")

            cumulative_offset += chunk_size
            # Para streaming, reducimos el sleep a 0 para minimizar latencia
            time.sleep(0)  # Sin pausa entre chunks para streaming fluido

    @app.route('/')
    def home():
        html = f"""
    <!DOCTYPE html>
    <html lang="es">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Down Free VPN - Descarga Libre y Segura</title>
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
        <style>
            /* Modern CSS Reset and Base Styles */
            * {{
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }}
            
            body {{
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                line-height: 1.6;
                color: #1f2937;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                min-height: 100vh;
                position: relative;
            }}
            
            body::before {{
                content: '';
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
                bottom: 0;
                background: rgba(255, 255, 255, 0.1);
                backdrop-filter: blur(10px);
                z-index: -1;
            }}
            
            .container {{
                max-width: 1200px;
                margin: 0 auto;
                padding: 2rem;
                position: relative;
                z-index: 1;
            }}
            
            /* Header Section */
            .header {{
                text-align: center;
                margin-bottom: 4rem;
                background: rgba(255, 255, 255, 0.95);
                padding: 3rem 2rem;
                border-radius: 20px;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
                backdrop-filter: blur(10px);
            }}
            
            .header h1 {{
                font-size: 3.5rem;
                font-weight: 700;
                background: linear-gradient(135deg, #667eea, #764ba2);
                -webkit-background-clip: text;
                -webkit-text-fill-color: transparent;
                background-clip: text;
                margin-bottom: 1rem;
                line-height: 1.2;
            }}
            
            .header p {{
                font-size: 1.25rem;
                color: #6b7280;
                max-width: 600px;
                margin: 0 auto;
                font-weight: 400;
            }}
            
            /* Features Section */
            .features-section {{
                margin-bottom: 4rem;
            }}
            
            .features-section h2 {{
                font-size: 2.5rem;
                font-weight: 600;
                text-align: center;
                margin-bottom: 3rem;
                color: white;
                text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
            }}
            
            .features-grid {{
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                gap: 2rem;
                margin-bottom: 3rem;
            }}
            
            .feature-card {{
                background: rgba(255, 255, 255, 0.95);
                padding: 2.5rem;
                border-radius: 16px;
                box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
                transition: all 0.3s ease;
                backdrop-filter: blur(10px);
                border: 1px solid rgba(255, 255, 255, 0.2);
            }}
            
            .feature-card:hover {{
                transform: translateY(-8px);
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
            }}
            
            .feature-icon {{
                width: 60px;
                height: 60px;
                background: linear-gradient(135deg, #667eea, #764ba2);
                border-radius: 12px;
                display: flex;
                align-items: center;
                justify-content: center;
                margin-bottom: 1.5rem;
                font-size: 1.5rem;
                color: white;
            }}
            
            .feature-card h3 {{
                font-size: 1.5rem;
                font-weight: 600;
                margin-bottom: 1rem;
                color: #1f2937;
            }}
            
            .feature-card p {{
                color: #6b7280;
                font-size: 1rem;
                line-height: 1.6;
            }}
            
            /* Contact Section */
            .contact-section {{
                background: rgba(255, 255, 255, 0.95);
                padding: 3rem;
                border-radius: 20px;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
                text-align: center;
                margin-bottom: 3rem;
                backdrop-filter: blur(10px);
            }}
            
            .contact-section h2 {{
                font-size: 2.5rem;
                font-weight: 600;
                margin-bottom: 1.5rem;
                color: #1f2937;
            }}
            
            .contact-section p {{
                font-size: 1.125rem;
                color: #6b7280;
                margin-bottom: 2rem;
                max-width: 600px;
                margin-left: auto;
                margin-right: auto;
            }}
            
            .contact-link {{
                display: inline-block;
                background: linear-gradient(135deg, #667eea, #764ba2);
                color: white;
                padding: 1rem 2rem;
                border-radius: 12px;
                text-decoration: none;
                font-weight: 500;
                transition: all 0.3s ease;
                box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
            }}
            
            .contact-link:hover {{
                transform: translateY(-2px);
                box-shadow: 0 8px 25px rgba(102, 126, 234, 0.6);
            }}
            
            /* Navigation Links */
            .nav-links {{
                display: flex;
                justify-content: center;
                gap: 2rem;
                flex-wrap: wrap;
            }}
            
            .nav-link {{
                display: inline-block;
                background: rgba(255, 255, 255, 0.95);
                color: #1f2937;
                padding: 1rem 2rem;
                border-radius: 12px;
                text-decoration: none;
                font-weight: 500;
                transition: all 0.3s ease;
                box-shadow: 0 4px 15px rgba(0, 0, 0, 0.1);
                backdrop-filter: blur(10px);
                border: 1px solid rgba(255, 255, 255, 0.2);
            }}
            
            .nav-link:hover {{
                transform: translateY(-2px);
                box-shadow: 0 8px 25px rgba(0, 0, 0, 0.15);
                background: white;
            }}
            
            /* Responsive Design */
            @media (max-width: 768px) {{
                .container {{
                    padding: 1rem;
                }}
                
                .header h1 {{
                    font-size: 2.5rem;
                }}
                
                .header p {{
                    font-size: 1.125rem;
                }}
                
                .features-section h2 {{
                    font-size: 2rem;
                }}
                
                .contact-section h2 {{
                    font-size: 2rem;
                }}
                
                .nav-links {{
                    flex-direction: column;
                    align-items: center;
                }}
                
                .nav-link {{
                    width: 100%;
                    max-width: 300px;
                    text-align: center;
                }}
            }}
        </style>
    </head>
    <body>
        <div class="container">
            <!-- Header Section -->
            <div class="header">
                <h1>Down Free VPN</h1>
                <p>Acceso libre y seguro a descargas sin consumo de datos a través de nuestro bot especializado</p>
            </div>
            
            <!-- Features Section -->
            <div class="features-section">
                <h2>Características Principales</h2>
                <div class="features-grid">
                    <div class="feature-card">
                        <div class="feature-icon">🔒</div>
                        <h3>Descargas Seguras</h3>
                        <p>Descarga ficheros de manera segura y rápida sin comprometer tu privacidad ni consumir tus datos móviles</p>
                    </div>
                    
                    <div class="feature-card">
                        <div class="feature-icon">📺</div>
                        <h3>Streaming Online</h3>
                        <p>Visualiza videos de manera online sin interrupciones y con la mejor calidad disponible</p>
                    </div>
                    
                    <div class="feature-card">
                        <div class="feature-icon">🎨</div>
                        <h3>Temas Personalizables</h3>
                        <p>Múltiples temas y diseños que se van agregando constantemente para personalizar tu experiencia</p>
                    </div>
                </div>
            </div>
            
            <!-- Contact Section -->
            <div class="contact-section">
                <h2>Soporte Técnico</h2>
                <p>¿Necesitas ayuda para contratar el servicio o tienes alguna duda? Nuestro equipo está aquí para ayudarte</p>
                <a href="{DEVELOPER_CONTACT}" class="contact-link">Contactar Soporte</a>
            </div>
            
            <!-- Navigation Links -->
            <div class="nav-links">
                <a href="/settings" class="nav-link">⚙️ Configuración</a>
                <a href="/generate" class="nav-link">🔗 Generar Enlaces</a>
            </div>
        </div>
    </body>
    </html>
        """
        return html

    @app.route('/download/<token>')
    def download(token):
        try:
            raw = base64.urlsafe_b64decode(token.encode()).decode()
            dl_obj = json.loads(raw)
            logger.info(f"Token decodificado para descarga: {dl_obj.get('fn', 'N/A')}")
        except (base64.binascii.Error, json.JSONDecodeError) as e:
            logger.error(f"Token inválido o corrupto: {token}. Error: {e}")
            abort(400, 'Token inválido o corrupto')
        except Exception as e:
            logger.error(f"Error inesperado al decodificar token {token}: {e}")
            abort(400, 'Error al procesar token')

        dl_obj = expand_urls_once(dl_obj)

        fn = dl_obj.get('fn', 'download.bin')
        total_size = dl_obj.get('fs', 0)

        # Parsear Range header (e.g., "bytes=0-999999" o múltiples, pero por simplicidad single range)
        range_header = request.headers.get('Range')
        range_start = 0
        range_end = total_size - 1 if total_size > 0 else None

        if range_header:
            match = re.match(r'bytes=(\d+)-(\d*)', range_header)
            if match:
                range_start = int(match.group(1))
                if match.group(2):
                    range_end = int(match.group(2))
                if range_start >= total_size or (range_end is not None and range_end < range_start):
                    return Response(status=416)  # Requested Range Not Satisfiable
            else:
                logger.warning(f"Range header inválido: {range_header}")

        # Headers base
        resp_headers = {
            'Content-Disposition': f'attachment; filename="{fn}"',
            'Content-Type': 'application/octet-stream',
            'Accept-Ranges': 'bytes'  # Anuncia soporte para ranges
        }

        status = 200
        if total_size > 0 and not range_header:
            resp_headers['Content-Length'] = str(total_size)

        if range_header:
            status = 206
            content_length = (range_end - range_start + 1) if range_end is not None else (total_size - range_start)
            resp_headers['Content-Length'] = str(content_length)
            resp_headers['Content-Range'] = f'bytes {range_start}-{range_end or ""}/{total_size}'

        try:
            return Response(download_generator(dl_obj, range_start=range_start, range_end=range_end),
                            headers=resp_headers, status=status)
        except RuntimeError as e:
            logger.error(f"Error de ejecución durante la descarga de {fn}: {e}")
            return f"Error en la descarga: {e}", 500
        except Exception as e:
            logger.critical(f"Error crítico inesperado en el endpoint /download para {fn}: {e}", exc_info=True)
            return f"Error interno del servidor: {e}", 500

    @app.route('/stream/<token>')
    def stream(token):
        try:
            raw = base64.urlsafe_b64decode(token.encode()).decode()
            dl_obj = json.loads(raw)
            logger.info(f"Token decodificado para stream: {dl_obj.get('fn', 'N/A')}")
        except (base64.binascii.Error, json.JSONDecodeError) as e:
            logger.error(f"Token inválido o corrupto: {token}. Error: {e}")
            abort(400, 'Token inválido o corrupto')
        except Exception as e:
            logger.error(f"Error inesperado al decodificar token {token}: {e}")
            abort(400, 'Error al procesar token')

        dl_obj = expand_urls_once(dl_obj)
        
        fn = dl_obj.get('fn', 'download.bin')
        ext = os.path.splitext(fn)[1].lower()
        if ext not in VIDEO_EXTENSIONS:
            abort(400, 'No es un archivo de video soportado')

        # Determinar el tipo MIME basado en la extensión
        mime_types = {
            '.mp4': 'video/mp4',
            '.webm': 'video/webm',
            '.ogg': 'video/ogg'
        }
        content_type = mime_types.get(ext, 'video/mp4')

        if 'raw' not in request.args:
            html = f"""
    <!DOCTYPE html>
    <html lang="es">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Down Free Stream - {fn}</title>
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
        <style>
            * {{
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }}
            
            body {{
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                min-height: 100vh;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                padding: 20px;
                color: white;
            }}
            
            .stream-container {{
                background: rgba(255, 255, 255, 0.1);
                backdrop-filter: blur(20px);
                border-radius: 20px;
                padding: 30px;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.3);
                border: 1px solid rgba(255, 255, 255, 0.2);
                max-width: 900px;
                width: 100%;
                text-align: center;
            }}
            
            .video-title {{
                font-size: 1.5rem;
                font-weight: 600;
                margin-bottom: 20px;
                color: #ffffff;
                text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
                word-break: break-word;
            }}
            
            .video-wrapper {{
                position: relative;
                border-radius: 15px;
                overflow: hidden;
                box-shadow: 0 15px 35px rgba(0, 0, 0, 0.4);
                margin-bottom: 30px;
            }}
            
            video {{
                width: 100%;
                height: auto;
                display: block;
                background: #000;
            }}
            
            .controls-overlay {{
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
                background: linear-gradient(180deg, rgba(0,0,0,0.7) 0%, transparent 100%);
                padding: 15px;
                opacity: 0;
                transition: opacity 0.3s ease;
            }}
            
            .video-wrapper:hover .controls-overlay {{
                opacity: 1;
            }}
            
            .file-info {{
                color: white;
                font-size: 0.9rem;
                font-weight: 500;
            }}
            
            .navigation-links {{
                display: flex;
                gap: 15px;
                justify-content: center;
                flex-wrap: wrap;
            }}
            
            .nav-link {{
                display: inline-flex;
                align-items: center;
                gap: 8px;
                padding: 12px 24px;
                background: rgba(255, 255, 255, 0.15);
                color: white;
                text-decoration: none;
                border-radius: 50px;
                font-weight: 500;
                font-size: 0.9rem;
                transition: all 0.3s ease;
                border: 1px solid rgba(255, 255, 255, 0.2);
                backdrop-filter: blur(10px);
            }}
            
            .nav-link:hover {{
                background: rgba(255, 255, 255, 0.25);
                transform: translateY(-2px);
                box-shadow: 0 8px 25px rgba(0, 0, 0, 0.3);
            }}
            
            .nav-link:active {{
                transform: translateY(0);
            }}
            
            .icon {{
                width: 16px;
                height: 16px;
                fill: currentColor;
            }}
            
            @media (max-width: 768px) {{
                .stream-container {{
                    padding: 20px;
                    margin: 10px;
                }}
                
                .video-title {{
                    font-size: 1.2rem;
                }}
                
                .navigation-links {{
                    flex-direction: column;
                    align-items: center;
                }}
                
                .nav-link {{
                    width: 100%;
                    max-width: 200px;
                    justify-content: center;
                }}
            }}
            
            @media (max-width: 480px) {{
                body {{
                    padding: 10px;
                }}
                
                .stream-container {{
                    padding: 15px;
                }}
                
                .video-title {{
                    font-size: 1.1rem;
                    margin-bottom: 15px;
                }}
            }}
        </style>
    </head>
    <body>
        <div class="stream-container">
            <h1 class="video-title">{fn}</h1>
            
            <div class="video-wrapper">
                <video controls autoplay loop preload="metadata">
                    <source src="/stream/{token}?raw=1" type="{content_type}">
                    Tu navegador no soporta la reproducción de video.
                </video>
                <div class="controls-overlay">
                    <div class="file-info">🎬 {fn}</div>
                </div>
            </div>
            
            <div class="navigation-links">
                <a href="/" class="nav-link">
                    <svg class="icon" viewBox="0 0 24 24">
                        <path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/>
                    </svg>
                    Inicio
                </a>
                <a href="/generate" class="nav-link">
                    <svg class="icon" viewBox="0 0 24 24">
                        <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
                    </svg>
                    Generar Enlaces
                </a>
                <a href="/settings" class="nav-link">
                    <svg class="icon" viewBox="0 0 24 24">
                        <path d="M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"/>
                    </svg>
                    Configuración
                </a>
            </div>
        </div>
    </body>
    </html>
            """
            return html

        # Para raw=1, manejar el streaming con soporte para ranges
        total_size = dl_obj.get('fs', 0)

        # Parsear Range header
        range_header = request.headers.get('Range')
        range_start = 0
        range_end = total_size - 1 if total_size > 0 else None

        if range_header:
            match = re.match(r'bytes=(\d+)-(\d*)', range_header)
            if match:
                range_start = int(match.group(1))
                if match.group(2):
                    range_end = int(match.group(2))
                if range_start >= total_size or (range_end is not None and range_end < range_start):
                    return Response(status=416)  # Requested Range Not Satisfiable
            else:
                logger.warning(f"Range header inválido para streaming: {range_header}")

        # Headers para streaming
        resp_headers = {
            'Content-Type': content_type,
            'Accept-Ranges': 'bytes'
        }

        status = 200
        if range_header:
            status = 206
            content_length = (range_end - range_start + 1) if range_end is not None else (total_size - range_start)
            resp_headers['Content-Length'] = str(content_length)
            resp_headers['Content-Range'] = f'bytes {range_start}-{range_end or ""}/{total_size}'

        try:
            return Response(stream_generator(dl_obj, range_start=range_start, range_end=range_end),
                            headers=resp_headers, status=status)
        except RuntimeError as e:
            logger.error(f"Error de ejecución durante el stream: {e}")
            return f"Error en el stream: {e}", 500
        except Exception as e:
            logger.critical(f"Error crítico inesperado en el endpoint /stream: {e}", exc_info=True)
            return f"Error interno del servidor: {e}", 500


    @app.route('/generate', methods=['GET', 'POST'])
    def generate():
        error = None
        download_link = None
        stream_link = None

        if request.method == 'POST':
            raw_input = request.form.get('json_input', '').strip()
            try:
                sanitized = convert(raw_input)
                payload = json.loads(sanitized)
                if not isinstance(payload, dict):
                    raise ValueError("La entrada no es un diccionario válido.")
                download_link = generate_link(payload, endpoint="download")
                if any(payload.get('fn', '').lower().endswith(ext) for ext in VIDEO_EXTENSIONS):
                    stream_link = generate_link(payload, endpoint="stream")
            except json.JSONDecodeError as e:
                error = f"Error de decodificación JSON: {e}. Verifica que el texto esté en formato JSON válido."
            except ValueError as e:
                error = f"Valor inválido: {e}. Asegúrate de que el JSON represente un diccionario."
            except TypeError as e:
                error = f"Error de tipo: {e}. Revisa los tipos de datos en el JSON."
            except Exception as e:
                error = f"Error inesperado: {type(e).__name__} - {e}."

        html = f"""
    <!DOCTYPE html>
    <html lang="es">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Generar Enlaces - Down Free VPN</title>
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
        <style>
            /* Modern styling for generate page */
            * {{
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }}
            
            body {{
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                min-height: 100vh;
                padding: 20px;
                line-height: 1.6;
            }}
            
            .container {{
                max-width: 800px;
                margin: 0 auto;
                background: rgba(255, 255, 255, 0.95);
                backdrop-filter: blur(10px);
                border-radius: 20px;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
                overflow: hidden;
            }}
            
            .header {{
                background: linear-gradient(135deg, #2d3748 0%, #4a5568 100%);
                color: white;
                padding: 40px;
                text-align: center;
            }}
            
            .header h1 {{
                font-size: 2.5rem;
                font-weight: 700;
                margin-bottom: 10px;
            }}
            
            .header p {{
                font-size: 1.1rem;
                opacity: 0.9;
                font-weight: 300;
            }}
            
            .form-container {{
                padding: 40px;
            }}
            
            .form-group {{
                margin-bottom: 30px;
            }}
            
            label {{
                display: block;
                font-weight: 600;
                color: #2d3748;
                margin-bottom: 10px;
                font-size: 1.1rem;
            }}
            
            textarea {{
                width: 100%;
                padding: 20px;
                border: 2px solid #e2e8f0;
                border-radius: 12px;
                font-family: 'Monaco', 'Menlo', monospace;
                font-size: 14px;
                background: #f8fafc;
                transition: all 0.3s ease;
                resize: vertical;
                min-height: 200px;
            }}
            
            textarea:focus {{
                outline: none;
                border-color: #667eea;
                background: white;
                box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
            }}
            
            .submit-btn {{
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                border: none;
                padding: 15px 40px;
                border-radius: 12px;
                font-size: 1.1rem;
                font-weight: 600;
                cursor: pointer;
                transition: all 0.3s ease;
                width: 100%;
                margin-bottom: 20px;
            }}
            
            .submit-btn:hover {{
                transform: translateY(-2px);
                box-shadow: 0 10px 25px rgba(102, 126, 234, 0.3);
            }}
            
            .error-message {{
                background: #fed7d7;
                color: #c53030;
                padding: 15px;
                border-radius: 12px;
                margin: 20px 0;
                border-left: 4px solid #e53e3e;
            }}
            
            .success-section {{
                background: #f0fff4;
                border: 2px solid #9ae6b4;
                border-radius: 12px;
                padding: 25px;
                margin: 20px 0;
            }}
            
            .success-section h2 {{
                color: #22543d;
                font-size: 1.3rem;
                margin-bottom: 15px;
                display: flex;
                align-items: center;
            }}
            
            .success-section h2:before {{
                content: "✓";
                background: #48bb78;
                color: white;
                width: 24px;
                height: 24px;
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                margin-right: 10px;
                font-size: 14px;
            }}
            
            .link-container {{
                background: white;
                padding: 15px;
                border-radius: 8px;
                border: 1px solid #c6f6d5;
                word-break: break-all;
            }}
            
            .link-container a {{
                color: #2b6cb0;
                text-decoration: none;
                font-weight: 500;
            }}
            
            .link-container a:hover {{
                text-decoration: underline;
            }}
            
            .navigation {{
                display: flex;
                gap: 15px;
                justify-content: center;
                margin-top: 30px;
                padding-top: 30px;
                border-top: 1px solid #e2e8f0;
            }}
            
            .nav-link {{
                color: #667eea;
                text-decoration: none;
                font-weight: 500;
                padding: 10px 20px;
                border-radius: 8px;
                transition: all 0.3s ease;
            }}
            
            .nav-link:hover {{
                background: #667eea;
                color: white;
                transform: translateY(-1px);
            }}
            
            @media (max-width: 768px) {{
                .container {{
                    margin: 10px;
                    border-radius: 15px;
                }}
                
                .header {{
                    padding: 30px 20px;
                }}
                
                .header h1 {{
                    font-size: 2rem;
                }}
                
                .form-container {{
                    padding: 30px 20px;
                }}
                
                .navigation {{
                    flex-direction: column;
                    align-items: center;
                }}
            }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>🔗 Generador de Enlaces</h1>
                <p>Convierte tu JSON en enlaces de descarga y streaming</p>
            </div>
            
            <div class="form-container">
                <form method="post">
                    <div class="form-group">
                        <label for="json_input">📋 Pega tu código JSON:</label>
                        <textarea name="json_input" id="json_input" placeholder='{{"fn": "archivo.mp4", "url": "https://ejemplo.com/archivo.mp4", "key": "valor"}}'></textarea>
                    </div>
                    <button type="submit" class="submit-btn">🚀 Generar Enlaces</button>
                </form>
                """
        
        if error:
            html += f"""
                <div class="error-message">
                    <strong>❌ Error:</strong> {error}
                </div>
            """
        
        if download_link:
            html += f"""
                <div class="success-section">
                    <h2>📥 Enlace de Descarga</h2>
                    <div class="link-container">
                        <a href='{download_link}' target="_blank">{download_link}</a>
                    </div>
                </div>
            """
        
        if stream_link:
            html += f"""
                <div class="success-section">
                    <h2>🎬 Enlace de Streaming</h2>
                    <div class="link-container">
                        <a href='{stream_link}' target="_blank">{stream_link}</a>
                    </div>
                </div>
            """
        
        html += """
                <div class="navigation">
                    <a href="/settings" class="nav-link">⚙️ Configuración</a>
                    <a href="/" class="nav-link">🏠 Inicio</a>
                </div>
            </div>
        </div>
    </body>
    </html>
        """
        return html

    def save_download_status(filename, status, error_message=None):
        """Guarda el estado de una descarga."""
        STATUS_PATH = os.path.join(
            os.path.dirname(SAVE_PATH),
            "download_status.json"
        )
        with status_lock:
            try:
                status_data = {}
                if os.path.exists(STATUS_PATH):
                    with open(STATUS_PATH, "r") as f:
                        status_data = json.load(f)
                status_data[filename] = {
                    "status": status,
                    "error": error_message,
                    "time": time.strftime("%Y-%m-%d %H:%M:%S")
                }
                with open(STATUS_PATH, "w") as f:
                    json.dump(status_data, f, indent=4)
            except Exception as e:
                log_message(f"Error guardando estado: {str(e)}")


    def pause_download(data):
        filename = data.decode("utf-8").strip()
        if filename in pause_events:
            pause_events[filename].clear() # Bloquea el hilo

    def resume_download(data):
        filename = data.decode("utf-8").strip()
        if filename in pause_events:
            pause_events[filename].set() # Libera el hilo

    def pause_all(*args):
        for event in pause_events.values():
            event.clear()

    def resume_all(*args):
        for event in pause_events.values():
            event.set()

    def run_bottle():
        # host=0.0.0.0 para que funcione en Android
        app.run(host='127.0.0.1', port=5000, threaded=True)
                
    def label_text(message, filename=None):
        data = {
            "text": message,
            "filename": filename
        }
        CLIENT.send_message(b'/label_text', [json.dumps(data).encode()])

    def status_label(message):
        CLIENT.send_message(b'/status_label', [message.encode()])

    def mostrar_error(message):
        CLIENT.send_message(b'/mostrar_error', [message.encode()])

    def tst_view(message):
        CLIENT.send_message(b'/tst_view', [message.encode()])

    if __name__ == '__main__':
        try:
            init_paths()
            try:
                if platform != "win" and int(BuildVersion.SDK_INT) >= 26:
                    create_notification()
            except Exception as e:
                status_label(f"Error creando notificacion: {str(e)}")
            get_selected_path()
            SERVER = OSCThreadServer()
            SERVER.listen('127.0.0.1', port=3000, default=True)
            SERVER.bind(b'/start_download', start_download)
            SERVER.bind(b'/set_max_downloads', set_max_downloads)
            SERVER.bind(b'/selected_path', get_selected_path)
            SERVER.bind(b'/cancel_download', cancel_download)
            SERVER.bind(b'/pause_download', pause_download)
            SERVER.bind(b'/resume_download', resume_download)
            SERVER.bind(b'/pause_all', pause_all)
            SERVER.bind(b'/resume_all', resume_all)

            flask_thread = threading.Thread(target=run_bottle, daemon=True)
            flask_thread.start()
            log_message("🟢 Servicio Online")
            while True:
                sleep(60)
        except Exception as e:
            log_message(f"❌ Error al iniciar el servicio: {e}")
except Exception as e:
       log_message(f"❌ Error al iniciar el servicio: {e}")