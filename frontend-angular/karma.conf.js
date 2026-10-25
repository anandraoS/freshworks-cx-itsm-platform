// Karma config: headless Chrome. Set CHROME_BIN to a Chrome/Chromium binary, e.g.
//   CHROME_BIN="C:/Program Files/Google/Chrome/Application/chrome.exe" npm test
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
    ],
    client: { jasmine: { random: true }, clearContext: false },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: { base: 'ChromeHeadless', flags: ['--no-sandbox', '--disable-gpu'] },
    },
    restartOnFileChange: true,
  });
};
