import { test, expect } from '@playwright/test';

test.describe('Login Button', () => {

  test('login button is visible on desktop when not logged in', async ({ page }) => {
    await page.goto('/');
    // Login button should be visible in the desktop header (hidden md:flex)
    const loginButton = page.locator('div.hidden.md\\\\:flex a').filter({ hasText: 'Log in' });
    await expect(loginButton).toBeVisible();
  });

  test('login button links to Twitch OAuth', async ({ page }) => {
    await page.goto('/');
    const loginButton = page.locator('a').filter({ hasText: 'Log in' }).first();
    const href = await loginButton.getAttribute('href');
    expect(href).toContain('/auth/twitch/login');
  });

  test('login button appears in mobile menu when not logged in', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    await hamburgerButton.click();

    const mobileLinks = page.locator('div.space-y-1 a');
    const logInLink = mobileLinks.filter({ hasText: 'Log in' });
    await expect(logInLink).toBeVisible();
  });
});
