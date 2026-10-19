# PFC Clone (Android)

1. Открой папку в Android Studio (Koala или новее), дождись Gradle Sync.
2. Build > Build APK(s), установи на телефон (или Run).
3. В приложении: кнопка «1» -> включи службу «PFC Clone»; кнопка «2» -> разреши «Поверх окон».
4. Вставь скрипт, нажми СТАРТ, разреши запись экрана.
5. Остановка: красная кнопка СТОП на экране или «СТОП» в шторке.

API: pfc.log, pfc.sleep, pfc.click, pfc.longClick(x,y,ms), pfc.swipe(x1,y1,x2,y2,ms),
pfc.startScreenCapture(n), pfc.stopScreenCapture(), pfc.getColor(x,y) -> 0xRRGGBB,
pfc.getContoursCount(x1,y1,x2,y2) (приближённо), Time.getMillis(), EXIT.
