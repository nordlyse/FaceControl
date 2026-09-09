<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
  <#if section = "header">
  <#if face_first_enroll?has_content && face_first_enroll>
  Save your login photo (first time only)
  <#else>
  Face verification
  </#if>
  <#elseif section = "form">
  <form id="kc-face-form" action="${url.loginAction}" method="post" enctype="application/x-www-form-urlencoded">
    <input type="hidden" name="face_image" id="face_image" value="" />
    <div class="${properties.kcFormGroupClass!}">
      <p class="instruction"><#if face_first_enroll?has_content && face_first_enroll>This photo is stored securely and used on future sign-ins to compare with your camera. Use good lighting and a neutral expression.<#else>Use your camera: open preview, then tap Capture (image is resized in the browser).</#if></p>
      <p id="face_status" class="instruction" style="font-weight:600;min-height:1.5em;" aria-live="polite"></p>
      <div id="face_cam_wrap" style="display:none;margin-bottom:1rem;">
        <video id="face_video" playsinline muted style="max-width:100%;max-height:280px;border-radius:4px;background:#111;"></video>
        <div style="margin-top:0.5rem;">
          <button type="button" class="${properties.kcButtonClass!}" id="face_start_cam">Start camera</button>
          <button type="button" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" id="face_snap">Capture photo</button>
        </div>
      </div>
    </div>
    <div class="${properties.kcFormGroupClass!}">
      <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit" id="face_submit" value="Continue" />
    </div>
  </form>
  <script type="text/javascript">
  (function () {
    var form = document.getElementById('kc-face-form');
    var hiddenImg = document.getElementById('face_image');
    var video = document.getElementById('face_video');
    var camWrap = document.getElementById('face_cam_wrap');
    var btnStart = document.getElementById('face_start_cam');
    var btnSnap = document.getElementById('face_snap');
    var statusEl = document.getElementById('face_status');
    var stream = null;

    function setStatus(msg) {
      if (statusEl) statusEl.textContent = msg || '';
    }

    function setHiddenB64(b64) {
      if (hiddenImg) hiddenImg.value = b64 || '';
    }

    function compressSourceToJpegBase64(src, onDone) {
      var maxW = 480;
      var quality = 0.72;
      var canvas = document.createElement('canvas');
      var vw = src.videoWidth || src.naturalWidth || 0;
      var vh = src.videoHeight || src.naturalHeight || 0;
      if (!vw || !vh) {
        onDone('');
        return;
      }
      var ratio = Math.min(1, maxW / vw);
      canvas.width = Math.round(vw * ratio);
      canvas.height = Math.round(vh * ratio);
      var ctx = canvas.getContext('2d');
      try {
        ctx.drawImage(src, 0, 0, canvas.width, canvas.height);
        var dataUrl = canvas.toDataURL('image/jpeg', quality);
        var idx = dataUrl.indexOf(',');
        onDone(idx > -1 ? dataUrl.substring(idx + 1) : '');
      } catch (err) {
        onDone('');
      }
    }

    function showCamUi(show) {
      if (camWrap) camWrap.style.display = show ? 'block' : 'none';
    }

    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      showCamUi(true);
      if (btnStart) btnStart.addEventListener('click', function () {
        setStatus('');
        setHiddenB64('');
        if (stream) {
          stream.getTracks().forEach(function (t) { t.stop(); });
          stream = null;
        }
        navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }, audio: false }).then(function (s) {
          stream = s;
          video.srcObject = stream;
          return video.play();
        }).then(function () {
          setStatus('Camera on — wait for preview, then Capture.');
        }).catch(function () {
          alert('Camera permission denied or unavailable. Allow camera access for this site.');
        });
      });
      if (btnSnap) btnSnap.addEventListener('click', function () {
        var attempts = 0;
        function trySnap() {
          attempts++;
          var vw = video.videoWidth;
          var vh = video.videoHeight;
          if (!vw || !vh) {
            if (attempts > 40) {
              setStatus('Could not read camera frame. Click Start camera again, wait for preview, then Capture.');
              return;
            }
            setStatus('Waiting for camera frame…');
            window.setTimeout(trySnap, 75);
            return;
          }
          compressSourceToJpegBase64(video, function (b64) {
            setHiddenB64(b64);
            if (!b64) {
              setStatus('Capture failed (blank frame). Try Capture again.');
              return;
            }
            var kb = Math.round(b64.length / 1024);
            setStatus('Captured (~' + kb + ' KB base64). You can tap Continue.');
          });
        }
        if (!stream || !video.srcObject) {
          alert('Click Start camera first and wait until you see yourself.');
          return;
        }
        trySnap();
      });
    } else {
      showCamUi(false);
      setStatus('Camera API not supported in this browser. Use a current browser or secure context (HTTPS / localhost).');
    }

    if (form) form.addEventListener('submit', function (ev) {
      if (!hiddenImg || !hiddenImg.value) {
        ev.preventDefault();
        alert('Please capture a photo from the camera first.');
        return false;
      }
    });

    window.addEventListener('beforeunload', function () {
      if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
    });
  })();
  </script>
  </#if>
</@layout.registrationLayout>
