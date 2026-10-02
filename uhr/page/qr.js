import { createWidget, widget, align } from '@zos/ui'
import { LocalStorage } from '@zos/storage'
import { replace } from '@zos/router'
import { onKey, offKey, KEY_EVENT_CLICK } from '@zos/interaction'
import * as display from '@zos/display'

// Zeigt die gespeicherten Zaehlerstaende als QR-Code zum Abscannen mit dem Handy.

function two(n) {
  return n < 10 ? '0' + n : '' + n
}

function stamp() {
  const d = new Date()
  return two(d.getDate()) + '.' + two(d.getMonth() + 1) + '.' + d.getFullYear() +
    ' ' + two(d.getHours()) + ':' + two(d.getMinutes())
}

Page({
  build() {
    let top = 0
    let bottom = 0
    let note = ''
    try {
      const storage = new LocalStorage()
      top = Number(storage.getItem('top', 0)) || 0
      bottom = Number(storage.getItem('bottom', 0)) || 0
    } catch (e) {
      note = 'Speicher-Fehler'
    }

    // Nur ASCII, damit jeder QR-Scanner den Text richtig anzeigt.
    const content = 'Zaehler\noben: ' + top + '\nunten: ' + bottom + '\nStand: ' + stamp()

    try {
      createWidget(widget.QRCODE, {
        content: content,
        x: 118, y: 70, w: 230, h: 230,
        bg_x: 98, bg_y: 50, bg_w: 270, bg_h: 270,
        bg_radius: 12
      })
    } catch (e) {
      note = 'QR-Fehler: ' + e
    }

    createWidget(widget.TEXT, {
      x: 60, y: 332, w: 346, h: 36,
      text: note || 'oben ' + top + '   unten ' + bottom,
      text_size: 24,
      color: 0xffffff,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    createWidget(widget.TEXT, {
      x: 110, y: 372, w: 246, h: 30,
      text: 'Taste = zurueck',
      text_size: 18,
      color: 0x888888,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    // Waehrend des Scannens hell bleiben (zwei Minuten).
    try {
      display.setPageBrightTime({ brightTime: 120000 })
    } catch (e) {}

    try {
      onKey({
        callback: (key, keyEvent) => {
          if (keyEvent === KEY_EVENT_CLICK) {
            try {
              replace({ url: 'page/index' })
            } catch (e) {}
          }
          return true
        }
      })
    } catch (e) {}
  },

  onDestroy() {
    try {
      offKey()
    } catch (e) {}
  }
})
