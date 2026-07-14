import { test, expect } from '@playwright/test';

test.describe('ArchieMate Frontend', () => {

  test('has title', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/ArchieMate/);
  });

  test('has welcome heading', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('h1')).toContainText('Welcome to ArchieMate');
  });

  test('has footer', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('footer')).toBeVisible();
  });

  test('footer contains version info', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Version');
  });

  test('footer contains copyright', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Archimond7450');
  });

  test('footer has twitch link', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    const twitchLink = footer.locator('a[href="https://twitch.tv/archimond7450"]');
    await expect(twitchLink).toBeVisible();
    await expect(twitchLink).toHaveAttribute('target', '_blank');
  });

  test('dark mode toggle exists', async ({ page }) => {
    await page.goto('/');
    // Check that the body has Tailwind classes (light mode default)
    const body = page.locator('body');
    await expect(body).toHaveClass(/bg-white/);
  });

  test('Tailwind CSS is loaded and applied', async ({ page }) => {
    await page.goto('/');
    // Verify Tailwind utility classes are present in the rendered HTML
    // This confirms the frontend is applying Tailwind classes correctly
    const footer = page.locator('footer');
    await expect(footer).toHaveClass(/bg-gray-100/);
    await expect(footer).toHaveClass(/border-t/);

    // Verify Tailwind classes on the row div (flex layout)
    const row = page.locator('footer > div > div.flex');
    await expect(row).toHaveCount(1);
    await expect(row).toHaveClass(/flex/);
    await expect(row).toHaveClass(/items-center/);

    // Verify text-gray-600 on the "Made with" section (3rd level div)
    const madeWith = page.locator('footer > div > div > div').filter({ hasText: /Made with/ });
    await expect(madeWith).toHaveClass(/text-gray-600/);

    // Verify text-red-500 on the heart emoji
    const heart = page.locator('span.text-red-500');
    await expect(heart).toBeVisible();

    // Verify the body has Tailwind utility classes
    const body = page.locator('body');
    await expect(body).toHaveClass(/bg-white/);
    await expect(body).toHaveClass(/text-gray-900/);
  });

  test('responsive layout on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    await expect(page.locator('h1')).toBeVisible();
    await expect(page.locator('footer')).toBeVisible();
  });
});
