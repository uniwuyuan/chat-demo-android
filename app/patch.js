;(function () {
  if (window.__shellPatched) return
  window.__shellPatched = true

  var bridge = window.AndroidShell
  if (!bridge || !bridge.saveFile) return

  var origClick = HTMLAnchorElement.prototype.click

  HTMLAnchorElement.prototype.click = function () {
    try {
      var href = this.href || ''
      var download = this.getAttribute('download')
      if (download && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
        var name = download || 'chat-demo.zip'
        var xhr = new XMLHttpRequest()
        xhr.open('GET', href, true)
        xhr.responseType = 'blob'
        xhr.onload = function () {
          var blob = xhr.response
          var reader = new FileReader()
          reader.onloadend = function () {
            var dataUrl = String(reader.result || '')
            var comma = dataUrl.indexOf(',')
            if (comma < 0) return
            bridge.saveFile(name, (blob && blob.type) || 'application/octet-stream', dataUrl.slice(comma + 1))
          }
          reader.readAsDataURL(blob)
        }
        xhr.send()
        return
      }
    } catch (error) {
      // 交回原始行为
    }
    return origClick.apply(this, arguments)
  }
})()
