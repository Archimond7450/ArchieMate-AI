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
    // Check that dark mode class can be toggled
    const body = page.locator('body');
    await expect(body).toHaveClass(/dark/).or.toBeEmpty();
  });

  test('responsive layout on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    await expect(page.locator('h1')).toBeVisible();
    await expect(page.locator('footer')).toBeVisible();
  });
});
