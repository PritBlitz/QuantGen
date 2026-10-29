// Runs before first paint. Reads only the UI theme preference (never auth data).
;(function () {
  var theme = 'light'
  try {
    var stored = window.localStorage.getItem('qg.theme')
    if (stored === 'light' || stored === 'dark') theme = stored
  } catch (e) {
    /* storage blocked: keep the default */
  }
  document.documentElement.setAttribute('data-theme', theme)
})()
