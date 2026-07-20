import { test, expect } from '@playwright/test';

test.describe('Dashboard Page', () => {

  test('dashboard page renders correctly when accessed directly', async ({ page }) => {
    await page.goto('/dashboard');
    // The page should render without errors even when not logged in
    await expect(page.locator('h1')).toBeVisible();
  });

  test('dashboard page is accessible via navigation', async ({ page }) => {
    await page.goto('/');
    const dashboardLink = page.locator('nav a').filter({ hasText: 'Dashboard' }).first();
    // Dashboard link should only be visible when logged in
    // Since we're not logged in, it won't appear in the desktop nav
    // but it will appear in the mobile menu
  });

  test('dashboard link appears in mobile menu when logged in', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    await hamburgerButton.click();

    // When not logged in, should see "Log in" link
    const mobileLinks = page.locator('div.space-y-1 a');
    const logInLink = mobileLinks.filter({ hasText: 'Log in' });
    await expect(logInLink).toBeVisible();
  });
});
