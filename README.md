# Down Free

Gestor de descargas para Android diseñado para descargas eficientes, soporte de múltiples protocolos y uso optimizado de datos.

## Estructura del Repositorio

El proyecto está organizado en las siguientes ramas:

- **`main`**: Rama principal del repositorio con la información y descripción general del proyecto.
- **`kotlin`**: Versión actual y activa de la aplicación, portada a **Android nativo en Kotlin** utilizando **Jetpack Compose**, **Foreground Service**, optimización con **R8 / Minify**, integración con **MediaStore** (descargas en `Download/Down_Free/`), y diseño adaptado con **Hugeicons**.
- **`legacy`**: Versión original de la aplicación desarrollada en **Python** con el framework **Kivy / KivyMD** y configuración de empaquetado para **Buildozer**.

---

### Ramas disponibles

Para acceder al código de cada versión:

```bash
# Versión nativa en Kotlin (recomendada):
git checkout kotlin

# Versión legacy en Python / Kivy:
git checkout legacy
```
