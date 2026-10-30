// Themes the login page from the operator's runtime branding (TDD 14.2).
fetch('/api/v1/settings/branding', { headers: { Accept: 'application/json' } })
  .then((response) => (response.ok ? response.json() : null))
  .then((branding) => {
    if (!branding) {
      return;
    }
    const root = document.documentElement.style;
    root.setProperty('--primary', branding.primaryColor);
    root.setProperty('--font', `${branding.fontFamily}, system-ui, sans-serif`);
    document.title = `Sign in - ${branding.name}`;
    document.getElementById('brand-name').textContent = branding.name;
    document.getElementById('brand-tagline').textContent = branding.tagline || '';
    const logo = branding.logo && (branding.logo.urls.card || branding.logo.urls.thumb);
    if (logo) {
      const img = document.getElementById('logo');
      img.src = logo;
      img.alt = branding.name;
      img.hidden = false;
    }
  })
  .catch(() => {});
