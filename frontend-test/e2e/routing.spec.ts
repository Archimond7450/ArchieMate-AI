import { test, expect } from '@playwright/test';

test.describe('Routing', () => {

  test('navigating from home to about', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('h1')).toContainText('Welcome to ArchieMate');

    const aboutLink = page.locator('nav a').filter({ hasText: 'About' }).first();
    await aboutLink.click();
    await expect(page).toHaveURL('/about');
    await expect(page.locator('h1')).toContainText('About ArchieMate');
  });

  test('navigating from about to docs', async ({ page }) => {
    await page.goto('/about');
    await expect(page.locator('h1')).toContainText('About ArchieMate');

    const docsLink = page.locator('nav a').filter({ hasText: 'Docs' }).first();
    await docsLink.click();
    await expect(page).toHaveURL('/docs');
    await expect(page.locator('h1')).toContainText('Documentation');
  });

  test('navigating from docs to home', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('h1')).toContainText('Documentation');

    const homeLink = page.locator('nav a').filter({ hasText: 'Home' }).first();
    await homeLink.click();
    await expect(page).toHaveURL('/');
    await expect(page.locator('h1')).toContainText('Welcome to ArchieMate');
  });

  test('navigating via header links preserves footer', async ({ page }) => {
    const urls = ['/', '/about', '/docs'];
    for (const url of urls) {
      await page.goto(url);
      await expect(page.locator('footer')).toBeVisible();
      await expect(page.locator('footer')).toContainText('Archimond7450');
    }
  });

  test('navigating via header links preserves header', async ({ page }) => {
    const urls = ['/', '/about', '/docs'];
    for (const url of urls) {
      await page.goto(url);
      const header = page.locator('header');
      await expect(header).toBeVisible();
      await expect(header.locator('a').first()).toContainText('ArchieMate');
    }
  });

  test('header is active on current page', async ({ page }) => {
    await page.goto('/about');
    const aboutLink = page.locator('nav a').filter({ hasText: 'About' }).first();
    await expect(aboutLink).toHaveClass(/text-indigo-600/);

    await page.goto('/docs');
    const docsLink = page.locator('nav a').filter({ hasText: 'Docs' }).first();
    await expect(docsLink).toHaveClass(/text-indigo-600/);

    await page.goto('/');
    const homeLink = page.locator('nav a').filter({ hasText: 'Home' }).first();
    await expect(homeLink).toHaveClass(/text-indigo-600/);
  });

  test('clicking ArchieMate logo goes to home', async ({ page }) => {
    await page.goto('/about');
    const logo = page.locator('header a').first();
    await logo.click();
    await expect(page).toHaveURL('/');
  });
});
