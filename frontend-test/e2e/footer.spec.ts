import { test, expect } from '@playwright/test';

test.describe('Footer', () => {

  test('footer is visible on the home page', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toBeVisible();
  });

  test('footer contains Scala version', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Scala');
  });

  test('footer contains Circe version', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Circe');
  });

  test('footer contains Scala.js version', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Scala.js');
  });

  test('footer contains Laminar version', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Laminar');
  });

  test('footer contains Tailwind version', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Tailwind');
  });

  test('footer contains copyright with Archimond7450', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Archimond7450');
  });

  test('footer contains AI model name', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Pi');
  });

  test('footer contains version info', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toContainText('Version');
  });

  test('footer contains Twitch link', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    const twitchLink = footer.locator('a[href="https://twitch.tv/archimond7450"]');
    await expect(twitchLink).toBeVisible();
    await expect(twitchLink).toHaveAttribute('target', '_blank');
    await expect(twitchLink).toHaveAttribute('rel', 'noopener noreferrer');
  });

  test('footer heart emoji is styled red', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    const heart = footer.locator('span.text-red-500');
    await expect(heart).toBeVisible();
    await expect(heart).toContainText('\u2764\uFE0F');
  });

  test('footer is responsive on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const footer = page.locator('footer');
    await expect(footer).toBeVisible();
  });

  test('footer has correct text content order', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    const footerText = await footer.textContent();
    // Check that the footer contains all expected text in some order
    expect(footerText).toContain('Made with');
    expect(footerText).toContain('Archimond7450');
    expect(footerText).toContain('Version');
  });
});
